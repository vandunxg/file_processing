package com.vandunxg.file_processing.auth.adapter.out.amqp;

import java.time.Instant;
import java.util.UUID;

import com.vandunxg.common.amqp.publisher.AmqpEventPublisher;
import com.vandunxg.common.amqp.publisher.MessageRoute;
import com.vandunxg.file_processing.auth.application.port.out.SessionEventPublisherPort;
import com.vandunxg.file_processing.auth.configuration.AuthProperties;
import com.vandunxg.file_processing.auth.domain.event.SessionPersistEvent;
import com.vandunxg.file_processing.auth.domain.event.SessionRevocationEvent;
import com.vandunxg.file_processing.auth.domain.event.SessionRotationEvent;
import com.vandunxg.file_processing.auth.domain.model.RevocationReason;
import com.vandunxg.file_processing.auth.domain.model.Session;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-SESSION-EVENT-PUBLISHER")
public class RabbitSessionEventPublisherAdapter implements SessionEventPublisherPort {

  private final AmqpEventPublisher amqpEventPublisher;
  private final AuthProperties authProperties;

  @Override
  public void publishPersist(Session session) {
    SessionPersistEvent event =
        new SessionPersistEvent(
            session.getId(),
            session.getUserId(),
            session.getCredentialVersion(),
            session.getRefreshTokenHash(),
            session.getUserAgent(),
            session.getIpAddressHash(),
            session.getIssuedAt(),
            session.getLastUsedAt(),
            session.getExpiresAt(),
            session.getRevokedAt(),
            session.getRevokedReason());
    publish(
        routeFor(authProperties.amqp().routingKey().sessionPersist()),
        event,
        "persist",
        session.getId());
  }

  @Override
  public void publishRotation(UUID sessionId, String newRefreshTokenHash, Instant lastUsedAt) {
    publish(
        routeFor(authProperties.amqp().routingKey().sessionUpdate()),
        new SessionRotationEvent(sessionId, newRefreshTokenHash, lastUsedAt),
        "rotation",
        sessionId);
  }

  @Override
  public void publishRevocation(UUID sessionId, RevocationReason reason, Instant revokedAt) {
    publish(
        routeFor(authProperties.amqp().routingKey().sessionRevoke()),
        new SessionRevocationEvent(sessionId, null, reason, revokedAt),
        "revocation",
        sessionId);
  }

  @Override
  public void publishRevocationForUser(UUID userId, RevocationReason reason, Instant revokedAt) {
    publish(
        routeFor(authProperties.amqp().routingKey().sessionRevoke()),
        SessionRevocationEvent.forUser(userId, reason, revokedAt),
        "revocation-user",
        userId);
  }

  private MessageRoute routeFor(String routingKey) {
    return MessageRoute.of(authProperties.amqp().exchange(), routingKey);
  }

  private void publish(MessageRoute route, Object event, String kind, Object id) {
    amqpEventPublisher
        .publish(route, event)
        .exceptionally(
            ex -> {
              log.warn("[publish] failed session event kind={} id={}", kind, id, ex);
              return null;
            });
  }
}
