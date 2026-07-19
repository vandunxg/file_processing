package com.vandunxg.file_processing.auth.application.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import com.vandunxg.common.utils.HashUtils;
import com.vandunxg.common.utils.IdUtils;
import com.vandunxg.file_processing.auth.application.command.ForgotPasswordCommand;
import com.vandunxg.file_processing.auth.application.port.in.ForgotPasswordUseCase;
import com.vandunxg.file_processing.auth.application.port.out.AuditLogEventPublisherPort;
import com.vandunxg.file_processing.auth.application.port.out.AuthThrottlePort;
import com.vandunxg.file_processing.auth.application.port.out.EmailSenderPort;
import com.vandunxg.file_processing.auth.application.port.out.PasswordResetTokenRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.UserRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.VerificationTokenGeneratorPort;
import com.vandunxg.file_processing.auth.configuration.AuthProperties;
import com.vandunxg.file_processing.auth.domain.exception.AuthDomainException;
import com.vandunxg.file_processing.auth.domain.exception.AuthErrorCode;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import com.vandunxg.file_processing.auth.domain.model.AuditLogDomain;
import com.vandunxg.file_processing.auth.domain.model.OperationType;
import com.vandunxg.file_processing.auth.domain.model.PasswordResetToken;
import com.vandunxg.file_processing.auth.domain.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-FORGOT-PASSWORD")
public class ForgotPasswordService implements ForgotPasswordUseCase {

  private static final String IP_THROTTLE_PREFIX = "password-reset:ip:";
  private static final String IDENTIFIER_THROTTLE_PREFIX = "password-reset:identifier:";
  private static final Duration IP_WINDOW = Duration.ofHours(1);

  private final AuthThrottlePort throttlePort;
  private final UserRepositoryPort userRepositoryPort;
  private final PasswordResetTokenRepositoryPort tokenRepositoryPort;
  private final VerificationTokenGeneratorPort tokenGeneratorPort;
  private final EmailSenderPort emailSenderPort;
  private final AuditLogEventPublisherPort auditLogEventPublisherPort;
  private final AuthProperties authProperties;
  private final Clock clock;

  @Override
  @Transactional
  public void request(ForgotPasswordCommand command) {
    String normalizedIdentifier = User.normalize(command.getIdentifier());
    String ipHash = hash(command.getIpAddress());
    if (!throttlePort.tryConsume(
        IP_THROTTLE_PREFIX + ipHash,
        authProperties.passwordReset().ipMaxAttemptsPerHour(),
        IP_WINDOW)) {
      throw new AuthDomainException(AuthErrorCode.AUTH_RATE_LIMITED);
    }
    if (!throttlePort.tryConsume(
        IDENTIFIER_THROTTLE_PREFIX + hash(normalizedIdentifier),
        authProperties.passwordReset().identifierMaxAttemptsPerWindow(),
        authProperties.passwordReset().identifierWindow())) {
      throw new AuthDomainException(AuthErrorCode.AUTH_RATE_LIMITED);
    }

    User user =
        userRepositoryPort
            .findByNormalizedIdentifier(normalizedIdentifier)
            .orElseThrow(() -> new AuthDomainException(AuthErrorCode.USER_NOT_FOUND));
    Instant now = Instant.now(clock);
    tokenRepositoryPort.invalidateAllForUser(user.getId(), now);
    if (user.getStatus() == com.vandunxg.file_processing.auth.domain.model.UserStatus.DISABLED) {
      return;
    }

    String rawToken = tokenGeneratorPort.generate();
    PasswordResetToken token =
        PasswordResetToken.issue(
            IdUtils.nextId(),
            user.getId(),
            hash(rawToken),
            now,
            authProperties.passwordReset().tokenTtl(),
            ipHash);
    tokenRepositoryPort.save(token);

    AuditLog audit =
        AuditLog.builder()
            .id(IdUtils.nextId())
            .domain(AuditLogDomain.AUTH)
            .objectId(user.getId())
            .operation(OperationType.PASSWORD_RESET_REQUESTED)
            .changedBy(user.getId())
            .changedAt(now)
            .ipAddress(ipHash)
            .build();
    publishAfterCommit(user, rawToken, audit);
  }

  private void publishAfterCommit(User user, String rawToken, AuditLog audit) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      return;
    }
    String resetLink = authProperties.passwordReset().baseUrl() + "?token=" + rawToken;
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            try {
              auditLogEventPublisherPort.publish(audit);
            } catch (Exception e) {
              log.warn(
                  "[request] failed to publish password reset audit userId={}", user.getId(), e);
            }
            try {
              emailSenderPort.sendPasswordResetEmail(
                  user.getEmail(), user.getDisplayName(), resetLink);
            } catch (Exception e) {
              log.warn("[request] failed to send password reset email userId={}", user.getId(), e);
            }
          }
        });
  }

  private static String hash(String value) {
    return value == null ? null : HashUtils.sha256(value.getBytes(StandardCharsets.UTF_8));
  }
}
