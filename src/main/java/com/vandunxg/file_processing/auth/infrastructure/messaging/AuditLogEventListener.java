package com.vandunxg.file_processing.auth.infrastructure.messaging;

import com.vandunxg.common.amqp.model.MessageEnvelope;
import com.vandunxg.file_processing.auth.domain.AuditLogRepository;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-AUDIT-LOG-LISTENER")
public class AuditLogEventListener {

  private final AuditLogRepository auditLogRepository;

  @RabbitListener(queues = "${app.auth.amqp.queue.audit-log}")
  public void onAuditLogEvent(MessageEnvelope<AuditLog> envelope) {
    AuditLog auditLog = envelope.payload();
    log.debug("[onAuditLogEvent] received audit log event objectId={}", auditLog.getObjectId());
    auditLogRepository.record(auditLog);
  }
}
