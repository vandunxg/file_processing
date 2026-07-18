package com.vandunxg.file_processing.auth.adapter.in.amqp;

import com.vandunxg.file_processing.auth.application.port.out.AuditLogPort;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-AUDIT-LOG-LISTENER")
public class AuditLogEventListener {

  private final AuditLogPort auditLogPort;

  @RabbitListener(queues = "${app.auth.amqp.queue.audit-log}")
  public void onAuditLogEvent(AuditLog auditLog) {
    log.debug("[onAuditLogEvent] received audit log event objectId={}", auditLog.getObjectId());
    auditLogPort.record(auditLog);
  }
}
