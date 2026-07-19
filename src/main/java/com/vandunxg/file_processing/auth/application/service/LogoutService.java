package com.vandunxg.file_processing.auth.application.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;

import com.vandunxg.common.utils.HashUtils;
import com.vandunxg.common.utils.IdUtils;
import com.vandunxg.file_processing.auth.application.command.LogoutCommand;
import com.vandunxg.file_processing.auth.application.port.in.LogoutUseCase;
import com.vandunxg.file_processing.auth.application.port.out.AuditLogEventPublisherPort;
import com.vandunxg.file_processing.auth.application.port.out.SessionRepositoryPort;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import com.vandunxg.file_processing.auth.domain.model.AuditLogDomain;
import com.vandunxg.file_processing.auth.domain.model.OperationType;
import com.vandunxg.file_processing.auth.domain.model.RevocationReason;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-LOGOUT")
public class LogoutService implements LogoutUseCase {

  private final SessionRepositoryPort sessionRepositoryPort;
  private final AuditLogEventPublisherPort auditLogEventPublisherPort;
  private final Clock clock;

  @Override
  @Transactional
  public void logout(LogoutCommand command) {
    Instant now = Instant.now(clock);
    sessionRepositoryPort.revoke(command.getSessionId(), RevocationReason.LOGOUT, now);

    AuditLog audit =
        AuditLog.builder()
            .id(IdUtils.nextId())
            .domain(AuditLogDomain.AUTH)
            .objectId(command.getSessionId())
            .operation(OperationType.LOGOUT)
            .changedBy(command.getUserId())
            .changedAt(now)
            .ipAddress(hashIp(command.getIpAddress()))
            .build();
    publishAfterCommit(audit);

    log.info(
        "[logout] session revoked userId={} sid={}", command.getUserId(), command.getSessionId());
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
