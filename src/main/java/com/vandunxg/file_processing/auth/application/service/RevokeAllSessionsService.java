package com.vandunxg.file_processing.auth.application.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;

import com.vandunxg.common.utils.HashUtils;
import com.vandunxg.common.utils.IdUtils;
import com.vandunxg.file_processing.auth.application.command.RevokeAllSessionsCommand;
import com.vandunxg.file_processing.auth.application.port.in.RevokeAllSessionsUseCase;
import com.vandunxg.file_processing.auth.application.port.out.AuditLogEventPublisherPort;
import com.vandunxg.file_processing.auth.application.port.out.CredentialVersionCachePort;
import com.vandunxg.file_processing.auth.application.port.out.SessionRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.UserRepositoryPort;
import com.vandunxg.file_processing.auth.domain.exception.AuthDomainException;
import com.vandunxg.file_processing.auth.domain.exception.AuthErrorCode;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import com.vandunxg.file_processing.auth.domain.model.AuditLogDomain;
import com.vandunxg.file_processing.auth.domain.model.OperationType;
import com.vandunxg.file_processing.auth.domain.model.RevocationReason;
import com.vandunxg.file_processing.auth.domain.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-REVOKE-ALL")
public class RevokeAllSessionsService implements RevokeAllSessionsUseCase {

  private final UserRepositoryPort userRepositoryPort;
  private final SessionRepositoryPort sessionRepositoryPort;
  private final CredentialVersionCachePort credentialVersionCachePort;
  private final AuditLogEventPublisherPort auditLogEventPublisherPort;
  private final Clock clock;

  @Override
  @Transactional
  public void revokeAll(RevokeAllSessionsCommand command) {
    User user =
        userRepositoryPort
            .findById(command.getUserId())
            .orElseThrow(
                () -> {
                  log.warn("[revokeAll] user not found userId={}", command.getUserId());
                  return new AuthDomainException(AuthErrorCode.USER_NOT_FOUND);
                });

    Instant now = Instant.now(clock);
    user.bumpCredentialVersion(now);
    userRepositoryPort.save(user);
    credentialVersionCachePort.invalidate(command.getUserId());

    RevocationReason reason =
        command.getReason() == null ? RevocationReason.USER_TRIGGERED : command.getReason();
    int revoked = sessionRepositoryPort.revokeAllForUser(command.getUserId(), reason, now);

    AuditLog audit =
        AuditLog.builder()
            .id(IdUtils.nextId())
            .domain(AuditLogDomain.AUTH)
            .objectId(command.getUserId())
            .operation(OperationType.ALL_SESSIONS_REVOKED)
            .changedBy(command.getUserId())
            .changedAt(now)
            .ipAddress(hashIp(command.getIpAddress()))
            .build();
    publishAfterCommit(audit);

    log.info(
        "[revokeAll] revoked all sessions userId={} count={} reason={}",
        command.getUserId(),
        revoked,
        reason);
  }

  private void publishAfterCommit(AuditLog auditLog) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            try {
              auditLogEventPublisherPort.publish(auditLog);
            } catch (Exception e) {
              log.warn("[publishAfterCommit] failed to publish audit event", e);
            }
          }
        });
  }

  private static String hashIp(String ip) {
    return ip == null ? null : HashUtils.sha256(ip.getBytes(StandardCharsets.UTF_8));
  }
}
