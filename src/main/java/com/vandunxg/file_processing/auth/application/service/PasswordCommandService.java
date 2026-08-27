package com.vandunxg.file_processing.auth.application.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.vandunxg.common.utils.HashUtils;
import com.vandunxg.common.utils.IdUtils;
import com.vandunxg.file_processing.auth.application.AfterCommit;
import com.vandunxg.file_processing.auth.application.AuditTrail;
import com.vandunxg.file_processing.auth.application.AuthProperties;
import com.vandunxg.file_processing.auth.application.capability.AuthMetrics;
import com.vandunxg.file_processing.auth.application.capability.AuthThrottle;
import com.vandunxg.file_processing.auth.application.capability.CredentialVersionCache;
import com.vandunxg.file_processing.auth.application.capability.EmailSender;
import com.vandunxg.file_processing.auth.application.capability.PasswordHasher;
import com.vandunxg.file_processing.auth.application.capability.VerificationTokenGenerator;
import com.vandunxg.file_processing.auth.application.command.ChangePasswordCommand;
import com.vandunxg.file_processing.auth.application.command.ForgotPasswordCommand;
import com.vandunxg.file_processing.auth.application.command.ResetPasswordCommand;
import com.vandunxg.file_processing.auth.application.exception.AuthErrorCode;
import com.vandunxg.file_processing.auth.application.exception.AuthException;
import com.vandunxg.file_processing.auth.domain.PasswordResetTokenRepository;
import com.vandunxg.file_processing.auth.domain.SessionRepository;
import com.vandunxg.file_processing.auth.domain.UserRepository;
import com.vandunxg.file_processing.auth.domain.exception.AuthRuleViolation;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import com.vandunxg.file_processing.auth.domain.model.AuditLogDomain;
import com.vandunxg.file_processing.auth.domain.model.OperationType;
import com.vandunxg.file_processing.auth.domain.model.PasswordResetToken;
import com.vandunxg.file_processing.auth.domain.model.RevocationReason;
import com.vandunxg.file_processing.auth.domain.model.User;
import com.vandunxg.file_processing.auth.domain.model.UserStatus;
import com.vandunxg.file_processing.auth.domain.policy.PasswordPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every way a password changes: the authenticated change, the forced first-login change, and the
 * emailed reset flow. They share the password policy, the reuse check, and the rule that a new
 * password revokes every session and bumps the credential version.
 */
@Service
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-PASSWORD")
public class PasswordCommandService {

  private static final String IP_THROTTLE_PREFIX = "password-reset:ip:";
  private static final String IDENTIFIER_THROTTLE_PREFIX = "password-reset:identifier:";
  private static final Duration IP_WINDOW = Duration.ofHours(1);

  private final AuthThrottle authThrottle;
  private final UserRepository userRepository;
  private final PasswordResetTokenRepository tokenRepository;
  private final PasswordHasher passwordHasher;
  private final VerificationTokenGenerator verificationTokenGenerator;
  private final SessionRepository sessionRepository;
  private final CredentialVersionCache credentialVersionCache;
  private final EmailSender emailSender;
  private final AuditTrail auditTrail;
  private final AuthMetrics authMetrics;
  private final AuthProperties authProperties;
  private final Clock clock;

  private final PasswordPolicy passwordPolicy = new PasswordPolicy();

  /** Authenticated self-service password change. */
  @Transactional
  public void change(ChangePasswordCommand command) {
    change(command, false);
  }

  /** Forced first-login password change, authorised by a password-change token. */
  @Transactional
  public void complete(ChangePasswordCommand command) {
    change(command, true);
  }

  @Transactional
  public void requestReset(ForgotPasswordCommand command) {
    String normalizedIdentifier = User.normalize(command.identifier());
    String ipHash = hash(command.ipAddress());
    if (!authThrottle.tryConsume(
        IP_THROTTLE_PREFIX + ipHash,
        authProperties.passwordReset().ipMaxAttemptsPerHour(),
        IP_WINDOW)) {
      authMetrics.forgotPasswordRateLimited();
      throw new AuthException(AuthErrorCode.AUTH_RATE_LIMITED);
    }
    if (!authThrottle.tryConsume(
        IDENTIFIER_THROTTLE_PREFIX + hash(normalizedIdentifier),
        authProperties.passwordReset().identifierMaxAttemptsPerWindow(),
        authProperties.passwordReset().identifierWindow())) {
      authMetrics.forgotPasswordRateLimited();
      throw new AuthException(AuthErrorCode.AUTH_RATE_LIMITED);
    }

    User user =
        userRepository
            .findByNormalizedIdentifier(normalizedIdentifier)
            .orElseThrow(() -> new AuthException(AuthErrorCode.USER_NOT_FOUND));
    Instant now = Instant.now(clock);
    tokenRepository.invalidateAllForUser(user.getId(), now);
    if (user.getStatus() == UserStatus.DISABLED) {
      return;
    }

    String rawToken = verificationTokenGenerator.generate();
    tokenRepository.save(
        PasswordResetToken.issue(
            IdUtils.nextId(),
            user.getId(),
            hash(rawToken),
            now,
            authProperties.passwordReset().tokenTtl(),
            ipHash));
    authMetrics.passwordResetRequested();

    auditTrail.recordAfterCommit(
        audit(user.getId(), OperationType.PASSWORD_RESET_REQUESTED, now, ipHash));
    sendResetEmailAfterCommit(user, rawToken);
  }

  @Transactional
  public void reset(ResetPasswordCommand command) {
    PasswordResetToken token =
        tokenRepository
            .findByTokenHashForUpdate(hash(command.token()))
            .orElseThrow(() -> rejected(AuthErrorCode.AUTH_PASSWORD_RESET_TOKEN_INVALID));
    Instant now = Instant.now(clock);
    if (!token.isUsableAt(now)) {
      throw rejected(AuthErrorCode.AUTH_PASSWORD_RESET_TOKEN_INVALID);
    }

    User user =
        userRepository
            .findById(token.getUserId())
            .orElseThrow(() -> rejected(AuthErrorCode.AUTH_PASSWORD_RESET_TOKEN_INVALID));
    if (!user.isActive() && !user.isPendingVerify()) {
      throw rejected(AuthErrorCode.AUTH_PASSWORD_RESET_TOKEN_INVALID);
    }
    if (!Objects.equals(command.newPassword(), command.confirmPassword())) {
      throw rejected(AuthErrorCode.AUTH_PASSWORD_CONFIRMATION_MISMATCH);
    }
    if (!passwordPolicy
        .validate(command.newPassword(), user.getNormalizedUsername(), user.getNormalizedEmail())
        .valid()) {
      throw rejected(AuthErrorCode.AUTH_PASSWORD_POLICY_VIOLATION);
    }
    if (passwordHasher.matches(command.newPassword(), user.getPasswordHash())) {
      throw rejected(AuthErrorCode.AUTH_PASSWORD_SAME_AS_CURRENT);
    }

    try {
      token.consume(now);
      tokenRepository.save(token);
      user.resetPassword(passwordHasher.hash(command.newPassword()), now);
    } catch (AuthRuleViolation violation) {
      throw AuthException.of(violation);
    }
    userRepository.save(user);
    sessionRepository.revokeAllForUser(user.getId(), RevocationReason.PASSWORD_CHANGED, now);
    credentialVersionCache.invalidate(user.getId());
    authMetrics.passwordResetCompleted();

    auditTrail.recordAfterCommit(
        audit(
            user.getId(), OperationType.PASSWORD_RESET_COMPLETED, now, hash(command.ipAddress())));
  }

  private void change(ChangePasswordCommand command, boolean mustChangePassword) {
    User user =
        userRepository
            .findById(command.userId())
            .orElseThrow(() -> new AuthException(AuthErrorCode.USER_NOT_FOUND));
    if (mustChangePassword && (!user.isActive() || !user.isMustChangePassword())) {
      throw new AuthException(AuthErrorCode.AUTH_PASSWORD_CHANGE_TOKEN_INVALID);
    }
    if (!passwordHasher.matches(command.currentPassword(), user.getPasswordHash())) {
      throw new AuthException(AuthErrorCode.AUTH_CURRENT_PASSWORD_INVALID);
    }
    if (!Objects.equals(command.newPassword(), command.confirmPassword())) {
      throw new AuthException(AuthErrorCode.AUTH_PASSWORD_CONFIRMATION_MISMATCH);
    }
    if (!passwordPolicy
        .validate(command.newPassword(), user.getNormalizedUsername(), user.getNormalizedEmail())
        .valid()) {
      throw new AuthException(AuthErrorCode.AUTH_PASSWORD_POLICY_VIOLATION);
    }
    if (passwordHasher.matches(command.newPassword(), user.getPasswordHash())) {
      throw new AuthException(AuthErrorCode.AUTH_PASSWORD_REUSE_NOT_ALLOWED);
    }

    Instant now = Instant.now(clock);
    user.changePassword(passwordHasher.hash(command.newPassword()), now);
    userRepository.save(user);
    sessionRepository.revokeAllForUser(user.getId(), RevocationReason.PASSWORD_CHANGED, now);

    invalidateCacheAfterCommit(user.getId());
    auditTrail.recordAfterCommit(
        audit(user.getId(), OperationType.PASSWORD_CHANGED, now, hash(command.ipAddress())));
  }

  private void invalidateCacheAfterCommit(UUID userId) {
    AfterCommit.run(
        () -> {
          try {
            credentialVersionCache.invalidate(userId);
          } catch (Exception exception) {
            log.warn(
                "[invalidateCacheAfterCommit] failed to invalidate credential cache userId={}",
                userId,
                exception);
          }
        });
  }

  private void sendResetEmailAfterCommit(User user, String rawToken) {
    String resetLink = authProperties.passwordReset().baseUrl() + "?token=" + rawToken;
    AfterCommit.run(
        () -> {
          try {
            emailSender.sendPasswordResetEmail(user.getEmail(), user.getDisplayName(), resetLink);
          } catch (Exception exception) {
            log.warn(
                "[sendResetEmailAfterCommit] failed to send password reset email userId={}",
                user.getId(),
                exception);
          }
        });
  }

  private AuthException rejected(AuthErrorCode errorCode) {
    authMetrics.passwordResetRejected();
    return new AuthException(errorCode);
  }

  private static String hash(String value) {
    return value == null ? null : HashUtils.sha256(value.getBytes(StandardCharsets.UTF_8));
  }

  private static AuditLog audit(UUID userId, OperationType operation, Instant now, String ipHash) {
    return AuditLog.builder()
        .id(IdUtils.nextId())
        .domain(AuditLogDomain.AUTH)
        .objectId(userId)
        .operation(operation)
        .changedBy(userId)
        .changedAt(now)
        .ipAddress(ipHash)
        .build();
  }
}
