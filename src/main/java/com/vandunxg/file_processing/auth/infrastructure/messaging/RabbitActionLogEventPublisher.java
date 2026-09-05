package com.vandunxg.file_processing.auth.infrastructure.messaging;

import com.vandunxg.common.amqp.publisher.AmqpEventPublisher;
import com.vandunxg.common.amqp.publisher.MessageRoute;
import com.vandunxg.file_processing.auth.application.AuthProperties;
import com.vandunxg.file_processing.auth.application.capability.ActionLogEventPublisher;
import com.vandunxg.file_processing.auth.domain.model.ActionLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-ACTION-LOG-PUBLISHER")
public class RabbitActionLogEventPublisher implements ActionLogEventPublisher {

  private final AmqpEventPublisher amqpEventPublisher;
  private final AuthProperties authProperties;

  @Override
  public void publish(ActionLog actionLog) {
    MessageRoute route =
        MessageRoute.of(
            authProperties.amqp().exchange(), authProperties.amqp().routingKey().actionLog());
    amqpEventPublisher
        .publish(route, actionLog)
        .exceptionally(
            ex -> {
              log.warn(
                  "[publish] failed to publish action log event path={} status={}",
                  actionLog.getPath(),
                  actionLog.getStatusCode(),
                  ex);
              return null;
            });
  }
}
