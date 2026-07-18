package com.vandunxg.file_processing.auth.application.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;

import com.vandunxg.common.utils.HashUtils;
import com.vandunxg.common.utils.IdUtils;
import com.vandunxg.file_processing.auth.application.command.ResendVerificationEmailCommand;
import com.vandunxg.file_processing.auth.application.port.in.ResendVerificationEmailUseCase;
import com.vandunxg.file_processing.auth.application.port.out.AuditLogPort;
import com.vandunxg.file_processing.auth.application.port.out.EmailSenderPort;
import com.vandunxg.file_processing.auth.application.port.out.EmailVerificationTokenRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.RegisterThrottlePort;
import com.vandunxg.file_processing.auth.application.port.out.UserRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.VerificationTokenGeneratorPort;
import com.vandunxg.file_processing.auth.configuration.AuthProperties;
import com.vandunxg.file_processing.auth.domain.exception.AuthDomainException;
import com.vandunxg.file_processing.auth.domain.exception.AuthErrorCode;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import com.vandunxg.file_processing.auth.domain.model.AuditLogDomain;
import com.vandunxg.file_processing.auth.domain.model.EmailVerificationToken;
import com.vandunxg.file_processing.auth.domain.model.OperationType;
import com.vandunxg.file_processing.auth.domain.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-RESEND-VERIFICATION")
public class ResendVerificationEmailService implements ResendVerificationEmailUseCase {

  private static final String THROTTLE_KEY_PREFIX = "resend:";

  private final RegisterThrottlePort throttlePort;
  private final UserRepositoryPort userRepositoryPort;
  private final EmailVerificationTokenRepositoryPort tokenRepositoryPort;
  private final AuditLogPort auditLogPort;
  private final VerificationTokenGeneratorPort tokenGeneratorPort;
  private final EmailSenderPort emailSenderPort;
  private final AuthProperties authProperties;
  private final Clock clock;

  @Override
  @Transactional
  public void resend(ResendVerificationEmailCommand command) {
    if (!throttlePort.tryConsume(
        THROTTLE_KEY_PREFIX + command.getIpAddress(),
        authProperties.emailVerification().resendMaxAttemptsPerHour())) {
      log.warn(
          "[resend] rate limited maxAttemptsPerHour={}",
          authProperties.emailVerification().resendMaxAttemptsPerHour());
      throw new AuthDomainException(AuthErrorCode.AUTH_RATE_LIMITED);
    }

    String normalizedIdentifier = User.normalize(command.getIdentifier());
    User user = userRepositoryPort.findByNormalizedIdentifier(normalizedIdentifier).orElse(null);
    if (user == null || !user.isPendingVerify()) {
      log.info("[resend] no-op");
      return;
    }

    Instant now = Instant.now(clock);
    tokenRepositoryPort.invalidateAllForUser(user.getId(), now);

    String rawToken = tokenGeneratorPort.generate();
    String tokenHash = HashUtils.sha256(rawToken.getBytes(StandardCharsets.UTF_8));
    String ipHash =
        command.getIpAddress() == null
            ? null
            : HashUtils.sha256(command.getIpAddress().getBytes(StandardCharsets.UTF_8));

    EmailVerificationToken token =
        EmailVerificationToken.issue(
            IdUtils.nextId(),
            user.getId(),
            tokenHash,
            now,
            authProperties.emailVerification().tokenTtl(),
            ipHash);
    tokenRepositoryPort.save(token);

    auditLogPort.record(
        AuditLog.builder()
            .id(IdUtils.nextId())
            .domain(AuditLogDomain.AUTH)
            .objectId(user.getId())
            .operation(OperationType.EMAIL_VERIFICATION_REQUESTED)
            .changedBy(user.getId())
            .changedAt(now)
            .ipAddress(ipHash)
            .build());

    log.info("[resend] issued new verification token userId={}", user.getId());

    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      String verificationLink = authProperties.emailVerification().baseUrl() + "?token=" + rawToken;
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              try {
                emailSenderPort.sendVerificationEmail(
                    user.getEmail(), user.getDisplayName(), verificationLink);
              } catch (Exception e) {
                log.warn(
                    "[resend] failed to send verification email after commit userId={}",
                    user.getId(),
                    e);
              }
            }
          });
    }
  }
}
