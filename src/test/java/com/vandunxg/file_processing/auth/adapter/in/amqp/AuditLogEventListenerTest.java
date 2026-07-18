package com.vandunxg.file_processing.auth.adapter.in.amqp;

import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.UUID;

import com.vandunxg.file_processing.auth.application.port.out.AuditLogPort;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import com.vandunxg.file_processing.auth.domain.model.AuditLogDomain;
import com.vandunxg.file_processing.auth.domain.model.OperationType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuditLogEventListenerTest {

  @Mock private AuditLogPort auditLogPort;

  @Test
  void onAuditLogEvent_delegatesToAuditLogPort() {
    AuditLogEventListener listener = new AuditLogEventListener(auditLogPort);
    AuditLog auditLog =
        AuditLog.builder()
            .id(UUID.randomUUID())
            .domain(AuditLogDomain.AUTH)
            .objectId(UUID.randomUUID())
            .operation(OperationType.USER_REGISTERED)
            .changedBy(UUID.randomUUID())
            .changedAt(Instant.now())
            .build();

    listener.onAuditLogEvent(auditLog);

    verify(auditLogPort).record(auditLog);
  }
}
