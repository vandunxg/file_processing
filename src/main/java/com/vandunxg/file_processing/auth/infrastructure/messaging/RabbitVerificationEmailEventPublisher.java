package com.vandunxg.file_processing.auth.infrastructure.messaging;

import com.vandunxg.common.amqp.publisher.AmqpEventPublisher;
import com.vandunxg.common.amqp.publisher.MessageRoute;
import com.vandunxg.common.utils.StrUtils;
import com.vandunxg.file_processing.auth.application.AuthProperties;
import com.vandunxg.file_processing.auth.application.capability.VerificationEmailEventPublisher;
import com.vandunxg.file_processing.auth.domain.event.SendVerificationEmailEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-EMAIL-EVENT-PUBLISHER")
public class RabbitVerificationEmailEventPublisher implements VerificationEmailEventPublisher {

  private final AmqpEventPublisher amqpEventPublisher;
  private final AuthProperties authProperties;

  @Override
  public void publish(String toEmail, String displayName, String verificationLink) {
    MessageRoute route =
        MessageRoute.of(
            authProperties.amqp().exchange(),
            authProperties.amqp().routingKey().verificationEmail());
    // Never log verificationLink here: it carries the raw opaque token.
    amqpEventPublisher
        .publish(route, new SendVerificationEmailEvent(toEmail, displayName, verificationLink))
        .exceptionally(
            ex -> {
              log.warn(
                  "[publish] failed to publish verification email event toEmail={}",
                  StrUtils.emailFormat(toEmail),
                  ex);
              return null;
            });
  }
}
