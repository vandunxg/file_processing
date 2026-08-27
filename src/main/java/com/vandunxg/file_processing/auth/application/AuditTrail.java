package com.vandunxg.file_processing.auth.application;

import com.vandunxg.file_processing.auth.application.capability.AuditLogEventPublisher;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
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
