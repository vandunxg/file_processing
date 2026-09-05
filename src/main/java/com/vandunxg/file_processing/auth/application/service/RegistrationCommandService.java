package com.vandunxg.file_processing.auth.application.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.vandunxg.common.utils.HashUtils;
import com.vandunxg.common.utils.IdUtils;
import com.vandunxg.common.utils.StrUtils;
import com.vandunxg.file_processing.auth.application.AfterCommit;
import com.vandunxg.file_processing.auth.application.AuditTrail;
import com.vandunxg.file_processing.auth.application.AuthProperties;
import com.vandunxg.file_processing.auth.application.capability.AuthThrottle;
import com.vandunxg.file_processing.auth.application.capability.PasswordHasher;
import com.vandunxg.file_processing.auth.application.capability.VerificationEmailEventPublisher;
import com.vandunxg.file_processing.auth.application.capability.VerificationTokenGenerator;
import com.vandunxg.file_processing.auth.application.command.RegisterCommand;
import com.vandunxg.file_processing.auth.application.command.ResendVerificationEmailCommand;
import com.vandunxg.file_processing.auth.application.command.VerifyEmailCommand;
import com.vandunxg.file_processing.auth.application.exception.AuthErrorCode;
import com.vandunxg.file_processing.auth.application.exception.AuthException;
import com.vandunxg.file_processing.auth.application.mapper.UserResultMapper;
import com.vandunxg.file_processing.auth.application.result.RegisterResult;
import com.vandunxg.file_processing.auth.domain.EmailVerificationTokenRepository;
import com.vandunxg.file_processing.auth.domain.RoleRepository;
import com.vandunxg.file_processing.auth.domain.UserRepository;
import com.vandunxg.file_processing.auth.domain.exception.AuthRuleViolation;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import com.vandunxg.file_processing.auth.domain.model.AuditLogDomain;
import com.vandunxg.file_processing.auth.domain.model.EmailVerificationToken;
import com.vandunxg.file_processing.auth.domain.model.OperationType;
import com.vandunxg.file_processing.auth.domain.model.Role;
import com.vandunxg.file_processing.auth.domain.model.User;
import com.vandunxg.file_processing.auth.domain.model.UserRole;
import com.vandunxg.file_processing.auth.domain.policy.PasswordPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Self-service account lifecycle up to a verified account: registration, email verification, and
 * re-issuing a verification email. The three use cases share the token-issuing and
 * email-after-commit rules, which is why they live together.
 */
@Service
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-REGISTRATION")
public class RegistrationCommandService {

  private static final String OPERATOR_ROLE_CODE = "OPERATOR";
  private static final String REGISTER_THROTTLE_KEY_PREFIX = "register:";
  private static final String RESEND_THROTTLE_KEY_PREFIX = "resend:";
  private static final Duration THROTTLE_WINDOW = Duration.ofHours(1);

  private final AuthThrottle authThrottle;
  private final UserRepository userRepository;
  private final RoleRepository roleRepository;
  private final EmailVerificationTokenRepository tokenRepository;
  private final PasswordHasher passwordHasher;
  private final VerificationTokenGenerator verificationTokenGenerator;
  private final VerificationEmailEventPublisher verificationEmailEventPublisher;
  private final UserResultMapper userResultMapper;
  private final AuditTrail auditTrail;
  private final AuthProperties authProperties;
  private final Clock clock;

  private final PasswordPolicy passwordPolicy = new PasswordPolicy();

  @Transactional
  public RegisterResult register(RegisterCommand command) {
    if (!authThrottle.tryConsume(
        REGISTER_THROTTLE_KEY_PREFIX + command.ipAddress(),
        authProperties.register().maxAttemptsPerHour(),
        THROTTLE_WINDOW)) {
      log.warn(
          "[register] rate limited maxAttemptsPerHour={}",
          authProperties.register().maxAttemptsPerHour());
      throw new AuthException(AuthErrorCode.AUTH_RATE_LIMITED);
    }

    String normalizedUsername = User.normalize(command.username());
    String normalizedEmail = User.normalize(command.email());

    PasswordPolicy.ValidationResult validation =
        passwordPolicy.validate(command.password(), normalizedUsername, normalizedEmail);
    if (!validation.valid()) {
      log.warn(
          "[register] password policy violation username={} reason={}",
          normalizedUsername,
          validation.reason());
      throw new AuthException(AuthErrorCode.AUTH_PASSWORD_POLICY_VIOLATION);
    }

    if (userRepository.existsByNormalizedUsername(normalizedUsername)) {
      log.warn("[register] duplicate username username={}", normalizedUsername);
      throw new AuthException(AuthErrorCode.AUTH_USERNAME_ALREADY_EXISTS);
    }
    if (userRepository.existsByNormalizedEmail(normalizedEmail)) {
      log.warn("[register] duplicate email email={}", StrUtils.emailFormat(normalizedEmail));
      throw new AuthException(AuthErrorCode.AUTH_EMAIL_ALREADY_EXISTS);
    }

    Role operatorRole =
        roleRepository
            .findByCode(OPERATOR_ROLE_CODE)
            .orElseThrow(
                () -> {
                  log.warn("[register] operator role not found code={}", OPERATOR_ROLE_CODE);
                  return new AuthException(AuthErrorCode.ROLE_INVALID);
                });

    Instant now = Instant.now(clock);
    String passwordHash = passwordHasher.hash(command.password());
    User user;
    try {
      user =
          User.register(
              command.username(),
              command.email(),
              command.displayName(),
              passwordHash,
              operatorRole,
              now);
    } catch (AuthRuleViolation violation) {
      throw AuthException.of(violation);
    }

    User saved;
    try {
      saved = userRepository.save(user);
    } catch (DataIntegrityViolationException e) {
      String cause =
          Optional.ofNullable(e.getMostSpecificCause()).map(Throwable::getMessage).orElse("");
      if (cause.contains("auth_users_normalized_email_uk")) {
        log.warn(
            "[register] concurrent duplicate email detected on save email={}",
            StrUtils.emailFormat(normalizedEmail));
        throw new AuthException(AuthErrorCode.AUTH_EMAIL_ALREADY_EXISTS);
      }
      if (cause.contains("auth_users_normalized_username_uk")) {
        log.warn(
            "[register] concurrent duplicate username detected on save username={}",
            normalizedUsername);
        throw new AuthException(AuthErrorCode.AUTH_USERNAME_ALREADY_EXISTS);
      }
      // Any other constraint is not a duplicate registration: reporting it as one would send the
      // caller chasing a username that is perfectly free.
      log.error("[register] unexpected constraint violation on save", e);
      throw e;
    }

    userRepository.assignRole(new UserRole(saved.getId(), operatorRole.getId()));

    String ipHash = hashIp(command.ipAddress());
    String rawToken = issueVerificationToken(saved.getId(), now, ipHash);

    log.info("[register] registered user userId={} status={}", saved.getId(), saved.getStatus());

    auditTrail.recordAfterCommit(audit(saved.getId(), OperationType.USER_REGISTERED, now, ipHash));
    sendVerificationEmailAfterCommit(saved, rawToken);

    return userResultMapper.toRegisterResult(saved);
  }

  @Transactional
  public RegisterResult verifyEmail(VerifyEmailCommand command) {
    String tokenHash = HashUtils.sha256(command.token().getBytes(StandardCharsets.UTF_8));

    EmailVerificationToken token =
        tokenRepository
            .findByTokenHashForUpdate(tokenHash)
            .orElseThrow(
                () -> {
                  log.warn("[verifyEmail] unknown token presented");
                  return new AuthException(AuthErrorCode.AUTH_EMAIL_VERIFICATION_TOKEN_INVALID);
                });

    Instant now = Instant.now(clock);
    try {
      token.consume(now);
    } catch (AuthRuleViolation violation) {
      log.warn("[verifyEmail] token consume rejected tokenId={}", token.getId());
      throw AuthException.of(violation);
    }
    tokenRepository.save(token);

    User user =
        userRepository
            .findById(token.getUserId())
            .orElseThrow(
                () -> {
                  log.warn(
                      "[verifyEmail] user not found for verified token tokenId={} userId={}",
                      token.getId(),
                      token.getUserId());
                  return new AuthException(AuthErrorCode.AUTH_EMAIL_VERIFICATION_TOKEN_INVALID);
                });

    try {
      user.verifyEmail(now);
    } catch (AuthRuleViolation violation) {
      throw AuthException.of(violation);
    }
    User saved = userRepository.save(user);

    log.info("[verifyEmail] verified email userId={} status={}", saved.getId(), saved.getStatus());

    auditTrail.recordAfterCommit(audit(saved.getId(), OperationType.EMAIL_VERIFIED, now, null));

    return userResultMapper.toRegisterResult(saved);
  }

  @Transactional
  public void resendVerificationEmail(ResendVerificationEmailCommand command) {
    if (!authThrottle.tryConsume(
        RESEND_THROTTLE_KEY_PREFIX + command.ipAddress(),
        authProperties.emailVerification().resendMaxAttemptsPerHour(),
        THROTTLE_WINDOW)) {
      log.warn(
          "[resendVerificationEmail] rate limited maxAttemptsPerHour={}",
          authProperties.emailVerification().resendMaxAttemptsPerHour());
      throw new AuthException(AuthErrorCode.AUTH_RATE_LIMITED);
    }

    String normalizedIdentifier = User.normalize(command.identifier());
    User user = userRepository.findByNormalizedIdentifier(normalizedIdentifier).orElse(null);
    if (user == null || !user.isPendingVerify()) {
      log.info("[resendVerificationEmail] no-op");
      return;
    }

    Instant now = Instant.now(clock);
    tokenRepository.invalidateAllForUser(user.getId(), now);

    String ipHash = hashIp(command.ipAddress());
    String rawToken = issueVerificationToken(user.getId(), now, ipHash);

    log.info("[resendVerificationEmail] issued new verification token userId={}", user.getId());

    auditTrail.recordAfterCommit(
        audit(user.getId(), OperationType.EMAIL_VERIFICATION_REQUESTED, now, ipHash));
    sendVerificationEmailAfterCommit(user, rawToken);
  }

  /** Persists a fresh verification token and returns the raw token to embed in the email link. */
  private String issueVerificationToken(UUID userId, Instant now, String ipHash) {
    String rawToken = verificationTokenGenerator.generate();
    tokenRepository.save(
        EmailVerificationToken.issue(
            IdUtils.nextId(),
            userId,
            HashUtils.sha256(rawToken.getBytes(StandardCharsets.UTF_8)),
            now,
            authProperties.emailVerification().tokenTtl(),
            ipHash));
    return rawToken;
  }

  private void sendVerificationEmailAfterCommit(User user, String rawToken) {
    String verificationLink = authProperties.emailVerification().baseUrl() + "?token=" + rawToken;
    AfterCommit.run(
        () -> {
          try {
            verificationEmailEventPublisher.publish(
                user.getEmail(), user.getDisplayName(), verificationLink);
          } catch (Exception exception) {
            log.warn(
                "[sendVerificationEmailAfterCommit] failed to publish verification email"
                    + " userId={}",
                user.getId(),
                exception);
          }
        });
  }

  private static String hashIp(String ipAddress) {
    return ipAddress == null ? null : HashUtils.sha256(ipAddress.getBytes(StandardCharsets.UTF_8));
  }

  private static AuditLog audit(UUID userId, OperationType operation, Instant now, String ipHash) {
    return AuditTrail.entry(AuditLogDomain.AUTH, userId, operation, userId, now)
        .ipAddress(ipHash)
        .build();
  }
}
