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
import com.vandunxg.file_processing.auth.domain.model.ActionLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RabbitActionLogEventPublisherTest {

  @Mock private AmqpEventPublisher amqpEventPublisher;

  private final AuthProperties authProperties =
      com.vandunxg.file_processing.testsupport.AuthPropertiesFixture.defaults();

  @Test
  void publish_sendsActionLogToTheConfiguredRoute() {
    when(amqpEventPublisher.publish(any(MessageRoute.class), any(ActionLog.class)))
        .thenReturn(CompletableFuture.completedFuture(null));
    RabbitActionLogEventPublisher publisher =
        new RabbitActionLogEventPublisher(amqpEventPublisher, authProperties);
    ActionLog actionLog = actionLog();

    publisher.publish(actionLog);

    verify(amqpEventPublisher)
        .publish(
            eq(
                MessageRoute.of(
                    authProperties.amqp().exchange(),
                    authProperties.amqp().routingKey().actionLog())),
            eq(actionLog));
  }

  @Test
  void publish_doesNotThrow_whenAmqpEventPublisherFails() {
    CompletableFuture<Void> failed = new CompletableFuture<>();
    failed.completeExceptionally(new RuntimeException("broker unavailable"));
    when(amqpEventPublisher.publish(any(MessageRoute.class), any(ActionLog.class)))
        .thenReturn(failed);
    RabbitActionLogEventPublisher publisher =
        new RabbitActionLogEventPublisher(amqpEventPublisher, authProperties);

    publisher.publish(actionLog());
  }

  private static ActionLog actionLog() {
    Instant now = Instant.now();
    return ActionLog.builder()
        .id(UUID.randomUUID())
        .username("operator")
        .startTime(now)
        .endTime(now)
        .duration(0L)
        .path("/api/v1/customers")
        .requestMethod("POST")
        .statusCode(500)
        .build();
  }
}
