package com.vandunxg.file_processing.auth.application.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;

import com.vandunxg.common.utils.HashUtils;
import com.vandunxg.common.utils.IdUtils;
import com.vandunxg.file_processing.auth.application.command.VerifyEmailCommand;
import com.vandunxg.file_processing.auth.application.port.in.VerifyEmailUseCase;
import com.vandunxg.file_processing.auth.application.port.out.AuditLogEventPublisherPort;
import com.vandunxg.file_processing.auth.application.port.out.EmailVerificationTokenRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.UserRepositoryPort;
import com.vandunxg.file_processing.auth.application.result.RegisterResult;
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
@Slf4j(topic = "AUTH-VERIFY-EMAIL")
public class VerifyEmailService implements VerifyEmailUseCase {

  private final EmailVerificationTokenRepositoryPort tokenRepositoryPort;
  private final UserRepositoryPort userRepositoryPort;
  private final AuditLogEventPublisherPort auditLogEventPublisherPort;
  private final Clock clock;

  @Override
  @Transactional
  public RegisterResult verifyEmail(VerifyEmailCommand command) {
    String tokenHash = HashUtils.sha256(command.getToken().getBytes(StandardCharsets.UTF_8));

    EmailVerificationToken token =
        tokenRepositoryPort
            .findByTokenHashForUpdate(tokenHash)
            .orElseThrow(
                () -> {
                  log.warn("[verifyEmail] unknown token presented");
                  return new AuthDomainException(AuthErrorCode.EMAIL_VERIFICATION_TOKEN_INVALID);
                });

    Instant now = Instant.now(clock);
    try {
      token.consume(now);
    } catch (AuthDomainException e) {
      log.warn("[verifyEmail] token consume rejected tokenId={}", token.getId());
      throw e;
    }
    tokenRepositoryPort.save(token);

    User user =
        userRepositoryPort
            .findById(token.getUserId())
            .orElseThrow(
                () -> {
                  log.warn(
                      "[verifyEmail] user not found for verified token tokenId={} userId={}",
                      token.getId(),
                      token.getUserId());
                  return new AuthDomainException(AuthErrorCode.EMAIL_VERIFICATION_TOKEN_INVALID);
                });

    user.verifyEmail(now);
    User saved = userRepositoryPort.save(user);

    AuditLog auditLog =
        AuditLog.builder()
            .id(IdUtils.nextId())
            .domain(AuditLogDomain.AUTH)
            .objectId(saved.getId())
            .operation(OperationType.EMAIL_VERIFIED)
            .changedBy(saved.getId())
            .changedAt(now)
            .build();

    log.info("[verifyEmail] verified email userId={} status={}", saved.getId(), saved.getStatus());

    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              try {
                auditLogEventPublisherPort.publish(auditLog);
              } catch (Exception e) {
                log.warn(
                    "[verifyEmail] failed to publish audit log event after commit userId={}",
                    saved.getId(),
                    e);
              }
            }
          });
    }

    return RegisterResult.from(saved);
  }
}
