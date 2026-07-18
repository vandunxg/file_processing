package com.vandunxg.file_processing.auth.adapter.out.amqp;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;

import com.vandunxg.common.amqp.publisher.AmqpEventPublisher;
import com.vandunxg.common.amqp.publisher.MessageRoute;
import com.vandunxg.file_processing.auth.configuration.AuthProperties;
import com.vandunxg.file_processing.auth.domain.event.SendVerificationEmailEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RabbitVerificationEmailEventPublisherAdapterTest {

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
  void publish_sendsEventToTheConfiguredRoute() {
    when(amqpEventPublisher.publish(any(MessageRoute.class), any(SendVerificationEmailEvent.class)))
        .thenReturn(CompletableFuture.completedFuture(null));
    RabbitVerificationEmailEventPublisherAdapter adapter =
        new RabbitVerificationEmailEventPublisherAdapter(amqpEventPublisher, authProperties);

    adapter.publish(
        "operator1@example.com", "Operator One", "https://app.example.com/verify?token=raw");

    verify(amqpEventPublisher)
        .publish(
            eq(MessageRoute.of("auth.events", "auth.email.verification-requested")),
            eq(
                new SendVerificationEmailEvent(
                    "operator1@example.com",
                    "Operator One",
                    "https://app.example.com/verify?token=raw")));
  }

  @Test
  void publish_doesNotThrow_whenAmqpEventPublisherFails() {
    CompletableFuture<Void> failed = new CompletableFuture<>();
    failed.completeExceptionally(new RuntimeException("broker unavailable"));
    when(amqpEventPublisher.publish(any(MessageRoute.class), any(SendVerificationEmailEvent.class)))
        .thenReturn(failed);
    RabbitVerificationEmailEventPublisherAdapter adapter =
        new RabbitVerificationEmailEventPublisherAdapter(amqpEventPublisher, authProperties);

    adapter.publish(
        "operator1@example.com", "Operator One", "https://app.example.com/verify?token=raw");
    // No exception propagates.
  }
}
