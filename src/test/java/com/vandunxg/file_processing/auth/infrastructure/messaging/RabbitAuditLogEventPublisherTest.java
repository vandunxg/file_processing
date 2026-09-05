package com.vandunxg.file_processing.auth.infrastructure.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.vandunxg.common.amqp.publisher.AmqpEventPublisher;
import com.vandunxg.common.amqp.publisher.MessageRoute;
import com.vandunxg.file_processing.auth.application.AuthProperties;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import com.vandunxg.file_processing.auth.domain.model.AuditLogDomain;
import com.vandunxg.file_processing.auth.domain.model.OperationType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RabbitAuditLogEventPublisherTest {

  @Mock private AmqpEventPublisher amqpEventPublisher;

  private final AuthProperties authProperties =
      com.vandunxg.file_processing.testsupport.AuthPropertiesFixture.defaults();

  @Test
  void publish_sendsAuditLogToTheConfiguredRoute() {
    when(amqpEventPublisher.publish(any(MessageRoute.class), any(AuditLog.class)))
        .thenReturn(CompletableFuture.completedFuture(null));
    RabbitAuditLogEventPublisher publisher =
        new RabbitAuditLogEventPublisher(amqpEventPublisher, authProperties);
    AuditLog auditLog =
        AuditLog.builder()
            .id(UUID.randomUUID())
            .domain(AuditLogDomain.AUTH)
            .objectId(UUID.randomUUID())
            .operation(OperationType.USER_REGISTERED)
            .changedBy(UUID.randomUUID())
            .changedAt(Instant.now())
            .build();

    publisher.publish(auditLog);

    verify(amqpEventPublisher)
        .publish(
            eq(
                MessageRoute.of(
                    authProperties.amqp().exchange(),
                    authProperties.amqp().routingKey().auditLog())),
            eq(auditLog));
  }

  @Test
  void publish_doesNotThrow_whenAmqpEventPublisherFails() {
    CompletableFuture<Void> failed = new CompletableFuture<>();
    failed.completeExceptionally(new RuntimeException("broker unavailable"));
    when(amqpEventPublisher.publish(any(MessageRoute.class), any(AuditLog.class)))
        .thenReturn(failed);
    RabbitAuditLogEventPublisher publisher =
        new RabbitAuditLogEventPublisher(amqpEventPublisher, authProperties);
    AuditLog auditLog =
        AuditLog.builder()
            .id(UUID.randomUUID())
            .domain(AuditLogDomain.AUTH)
            .objectId(UUID.randomUUID())
            .operation(OperationType.USER_REGISTERED)
            .changedBy(UUID.randomUUID())
            .changedAt(Instant.now())
            .build();

    publisher.publish(auditLog);
    // No exception propagates — the failure is only logged via .exceptionally().
  }
}
