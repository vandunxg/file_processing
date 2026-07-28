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
import com.vandunxg.file_processing.auth.domain.model.ActionLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RabbitActionLogEventPublisherAdapterTest {

  @Mock private AmqpEventPublisher amqpEventPublisher;

  private final AuthProperties authProperties =
      com.vandunxg.file_processing.testsupport.AuthPropertiesFixture.defaults();

  @Test
  void publish_sendsActionLogToTheConfiguredRoute() {
    when(amqpEventPublisher.publish(any(MessageRoute.class), any(ActionLog.class)))
        .thenReturn(CompletableFuture.completedFuture(null));
    RabbitActionLogEventPublisherAdapter adapter =
        new RabbitActionLogEventPublisherAdapter(amqpEventPublisher, authProperties);
    ActionLog actionLog = actionLog();

    adapter.publish(actionLog);

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
    RabbitActionLogEventPublisherAdapter adapter =
        new RabbitActionLogEventPublisherAdapter(amqpEventPublisher, authProperties);

    adapter.publish(actionLog());
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
