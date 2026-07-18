package com.vandunxg.file_processing.auth.adapter.out.amqp;

import com.vandunxg.common.amqp.publisher.AmqpEventPublisher;
import com.vandunxg.common.amqp.publisher.MessageRoute;
import com.vandunxg.file_processing.auth.application.port.out.AuditLogEventPublisherPort;
import com.vandunxg.file_processing.auth.configuration.AuthProperties;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-AUDIT-LOG-PUBLISHER")
public class RabbitAuditLogEventPublisherAdapter implements AuditLogEventPublisherPort {

  private final AmqpEventPublisher amqpEventPublisher;
  private final AuthProperties authProperties;

  @Override
  public void publish(AuditLog auditLog) {
    MessageRoute route =
        MessageRoute.of(
            authProperties.amqp().exchange(), authProperties.amqp().routingKey().auditLog());
    amqpEventPublisher
        .publish(route, auditLog)
        .exceptionally(
            ex -> {
              log.warn(
                  "[publish] failed to publish audit log event objectId={}",
                  auditLog.getObjectId(),
                  ex);
              return null;
            });
  }
}
