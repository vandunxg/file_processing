package com.vandunxg.file_processing.auth.application.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;

import com.vandunxg.common.utils.HashUtils;
import com.vandunxg.common.utils.IdUtils;
import com.vandunxg.file_processing.auth.application.command.RevokeSessionCommand;
import com.vandunxg.file_processing.auth.application.port.in.RevokeSessionUseCase;
import com.vandunxg.file_processing.auth.application.port.out.AuditLogEventPublisherPort;
import com.vandunxg.file_processing.auth.application.port.out.SessionRepositoryPort;
import com.vandunxg.file_processing.auth.domain.exception.AuthDomainException;
import com.vandunxg.file_processing.auth.domain.exception.AuthErrorCode;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import com.vandunxg.file_processing.auth.domain.model.AuditLogDomain;
import com.vandunxg.file_processing.auth.domain.model.OperationType;
import com.vandunxg.file_processing.auth.domain.model.RevocationReason;
import com.vandunxg.file_processing.auth.domain.model.Session;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-REVOKE-SESSION")
public class RevokeSessionService implements RevokeSessionUseCase {

  private final SessionRepositoryPort sessionRepositoryPort;
  private final AuditLogEventPublisherPort auditLogEventPublisherPort;
  private final Clock clock;

  @Override
  @Transactional
  public void revoke(RevokeSessionCommand command) {
    Instant now = Instant.now(clock);
    Session session =
        sessionRepositoryPort
            .findActiveById(command.getSessionId(), now)
            .orElseThrow(
                () -> {
                  log.warn("[revoke] session not found sid={}", command.getSessionId());
                  return new AuthDomainException(AuthErrorCode.SESSION_NOT_FOUND);
                });
    if (!session.getUserId().equals(command.getCallerUserId())) {
      log.warn(
          "[revoke] foreign session revoke attempt sid={} callerUserId={}",
          command.getSessionId(),
          command.getCallerUserId());
      throw new AuthDomainException(AuthErrorCode.SESSION_NOT_FOUND);
    }

    sessionRepositoryPort.revoke(command.getSessionId(), RevocationReason.USER_TRIGGERED, now);

    AuditLog audit =
        AuditLog.builder()
            .id(IdUtils.nextId())
            .domain(AuditLogDomain.AUTH)
            .objectId(command.getSessionId())
            .operation(OperationType.SESSION_REVOKED)
            .changedBy(command.getCallerUserId())
            .changedAt(now)
            .ipAddress(hashIp(command.getIpAddress()))
            .build();
    publishAfterCommit(audit);

    log.info(
        "[revoke] session revoked sid={} callerUserId={}",
        command.getSessionId(),
        command.getCallerUserId());
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
