package com.vandunxg.file_processing.auth.application;

import java.time.Instant;
import java.util.UUID;

import com.vandunxg.common.utils.IdUtils;
import com.vandunxg.file_processing.auth.application.capability.AuditLogEventPublisher;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import com.vandunxg.file_processing.auth.domain.model.AuditLogDomain;
import com.vandunxg.file_processing.auth.domain.model.OperationType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Single place where auth write use cases record an audit event.
 *
 * <p>Publication happens after commit so a rolled-back transaction leaves no audit trail, and a
 * broker failure is logged instead of propagated: losing an audit event must not fail the business
 * operation that already committed.
 */
@Service
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-AUDIT-TRAIL")
public class AuditTrail {

  private final AuditLogEventPublisher auditLogEventPublisher;

  /**
   * Starts an audit entry with the fields every entry must carry.
   *
   * <p>Kept here rather than copied into each command service so that adding a required field is
   * one edit instead of six, and so a service that forgets it cannot silently publish an incomplete
   * event. Callers add whatever else their operation records — {@code ipAddress}, {@code
   * userAgent}, {@code data} — and then {@code build()}.
   */
  public static AuditLog.AuditLogBuilder<?, ?> entry(
      AuditLogDomain domain, UUID objectId, OperationType operation, UUID actorId, Instant now) {
    return AuditLog.builder()
        .id(IdUtils.nextId())
        .domain(domain)
        .objectId(objectId)
        .operation(operation)
        .changedBy(actorId)
        .changedAt(now);
  }

  public void recordAfterCommit(AuditLog auditLog) {
    AfterCommit.run(
        () -> {
          try {
            auditLogEventPublisher.publish(auditLog);
          } catch (Exception exception) {
            log.warn(
                "[recordAfterCommit] failed to publish audit event operation={} objectId={}",
                auditLog.getOperation(),
                auditLog.getObjectId(),
                exception);
          }
        });
  }
}
