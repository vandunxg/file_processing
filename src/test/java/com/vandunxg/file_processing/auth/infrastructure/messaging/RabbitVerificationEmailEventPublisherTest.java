package com.vandunxg.file_processing.auth.infrastructure.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;

import com.vandunxg.common.amqp.publisher.AmqpEventPublisher;
import com.vandunxg.common.amqp.publisher.MessageRoute;
import com.vandunxg.file_processing.auth.application.AuthProperties;
import com.vandunxg.file_processing.auth.domain.event.SendVerificationEmailEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RabbitVerificationEmailEventPublisherTest {

  @Mock private AmqpEventPublisher amqpEventPublisher;

  private final AuthProperties authProperties =
      com.vandunxg.file_processing.testsupport.AuthPropertiesFixture.defaults();

  @Test
  void publish_sendsEventToTheConfiguredRoute() {
    when(amqpEventPublisher.publish(any(MessageRoute.class), any(SendVerificationEmailEvent.class)))
        .thenReturn(CompletableFuture.completedFuture(null));
    RabbitVerificationEmailEventPublisher publisher =
        new RabbitVerificationEmailEventPublisher(amqpEventPublisher, authProperties);

    publisher.publish(
        "operator1@example.com", "Operator One", "https://app.example.com/verify?token=raw");

    verify(amqpEventPublisher)
        .publish(
            eq(
                MessageRoute.of(
                    authProperties.amqp().exchange(),
                    authProperties.amqp().routingKey().verificationEmail())),
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
    RabbitVerificationEmailEventPublisher publisher =
        new RabbitVerificationEmailEventPublisher(amqpEventPublisher, authProperties);

    publisher.publish(
        "operator1@example.com", "Operator One", "https://app.example.com/verify?token=raw");
    // No exception propagates.
  }
}
