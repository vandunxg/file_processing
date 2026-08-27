package com.vandunxg.file_processing.auth.infrastructure.messaging;

import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.UUID;

import com.vandunxg.common.amqp.model.MessageEnvelope;
import com.vandunxg.file_processing.auth.domain.AuditLogRepository;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import com.vandunxg.file_processing.auth.domain.model.AuditLogDomain;
import com.vandunxg.file_processing.auth.domain.model.OperationType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuditLogEventListenerTest {

  @Mock private AuditLogRepository auditLogRepository;

  @Test
  void onAuditLogEvent_delegatesToAuditLogPort() {
    AuditLogEventListener listener = new AuditLogEventListener(auditLogRepository);
    AuditLog auditLog =
        AuditLog.builder()
            .id(UUID.randomUUID())
            .domain(AuditLogDomain.AUTH)
            .objectId(UUID.randomUUID())
            .operation(OperationType.USER_REGISTERED)
            .changedBy(UUID.randomUUID())
            .changedAt(Instant.now())
            .build();

    listener.onAuditLogEvent(MessageEnvelope.wrap(auditLog));

    verify(auditLogRepository).record(auditLog);
  }
}
