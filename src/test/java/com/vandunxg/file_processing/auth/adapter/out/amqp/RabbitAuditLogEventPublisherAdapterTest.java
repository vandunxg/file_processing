package com.vandunxg.file_processing.auth.adapter.out.amqp;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.vandunxg.common.amqp.publisher.AmqpEventPublisher;
import com.vandunxg.common.amqp.publisher.MessageRoute;
import com.vandunxg.file_processing.auth.configuration.AuthProperties;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import com.vandunxg.file_processing.auth.domain.model.AuditLogDomain;
import com.vandunxg.file_processing.auth.domain.model.OperationType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RabbitAuditLogEventPublisherAdapterTest {

  @Mock private AmqpEventPublisher amqpEventPublisher;

  private final AuthProperties authProperties =
      new AuthProperties(
          null,
          null,
          null,
          null,
          new AuthProperties.Amqp(
              "auth.events",
              new AuthProperties.Amqp.RoutingKey(
                  "auth.audit-log.recorded", "auth.email.verification-requested"),
              new AuthProperties.Amqp.Queue(
                  "auth.audit-log.queue", "auth.email-verification.queue")));

  @Test
  void publish_sendsAuditLogToTheConfiguredRoute() {
    when(amqpEventPublisher.publish(any(MessageRoute.class), any(AuditLog.class)))
        .thenReturn(CompletableFuture.completedFuture(null));
    RabbitAuditLogEventPublisherAdapter adapter =
        new RabbitAuditLogEventPublisherAdapter(amqpEventPublisher, authProperties);
    AuditLog auditLog =
        AuditLog.builder()
            .id(UUID.randomUUID())
            .domain(AuditLogDomain.AUTH)
            .objectId(UUID.randomUUID())
            .operation(OperationType.USER_REGISTERED)
            .changedBy(UUID.randomUUID())
            .changedAt(Instant.now())
            .build();

    adapter.publish(auditLog);

    verify(amqpEventPublisher)
        .publish(eq(MessageRoute.of("auth.events", "auth.audit-log.recorded")), eq(auditLog));
  }

  @Test
  void publish_doesNotThrow_whenAmqpEventPublisherFails() {
    CompletableFuture<Void> failed = new CompletableFuture<>();
    failed.completeExceptionally(new RuntimeException("broker unavailable"));
    when(amqpEventPublisher.publish(any(MessageRoute.class), any(AuditLog.class)))
        .thenReturn(failed);
    RabbitAuditLogEventPublisherAdapter adapter =
        new RabbitAuditLogEventPublisherAdapter(amqpEventPublisher, authProperties);
    AuditLog auditLog =
        AuditLog.builder()
            .id(UUID.randomUUID())
            .domain(AuditLogDomain.AUTH)
            .objectId(UUID.randomUUID())
            .operation(OperationType.USER_REGISTERED)
            .changedBy(UUID.randomUUID())
            .changedAt(Instant.now())
            .build();

    adapter.publish(auditLog);
    // No exception propagates — the failure is only logged via .exceptionally().
  }
}
