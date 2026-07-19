package com.vandunxg.file_processing.auth.adapter.in.amqp;

import com.vandunxg.common.amqp.model.MessageEnvelope;
import com.vandunxg.file_processing.auth.application.port.out.SessionArchivePort;
import com.vandunxg.file_processing.auth.domain.event.SessionPersistEvent;
import com.vandunxg.file_processing.auth.domain.event.SessionRevocationEvent;
import com.vandunxg.file_processing.auth.domain.event.SessionRotationEvent;
import com.vandunxg.file_processing.auth.domain.model.Session;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-SESSION-LISTENER")
public class SessionEventListener {

  private final SessionArchivePort sessionArchivePort;

  @RabbitListener(queues = "${app.auth.amqp.queue.session-persist}")
  public void onSessionPersist(MessageEnvelope<SessionPersistEvent> envelope) {
    SessionPersistEvent e = envelope.payload();
    log.debug("[onSessionPersist] sid={}", e.id());
    Session session =
        Session.builder()
            .id(e.id())
            .userId(e.userId())
            .credentialVersion(e.credentialVersion())
            .refreshTokenHash(e.refreshTokenHash())
            .userAgent(e.userAgent())
            .ipAddressHash(e.ipAddressHash())
            .issuedAt(e.createdAt())
            .lastUsedAt(e.lastUsedAt())
            .expiresAt(e.expiresAt())
            .revokedAt(e.revokedAt())
            .revokedReason(e.revokedReason())
            .build();
    sessionArchivePort.save(session);
  }

  @RabbitListener(queues = "${app.auth.amqp.queue.session-update}")
  public void onSessionRotation(MessageEnvelope<SessionRotationEvent> envelope) {
    SessionRotationEvent e = envelope.payload();
    log.debug("[onSessionRotation] sid={}", e.sessionId());
    sessionArchivePort.recordRotation(e.sessionId(), e.newRefreshTokenHash(), e.lastUsedAt());
  }

  @RabbitListener(queues = "${app.auth.amqp.queue.session-revoke}")
  public void onSessionRevocation(MessageEnvelope<SessionRevocationEvent> envelope) {
    SessionRevocationEvent e = envelope.payload();
    if (e.isBulkForUser()) {
      log.debug("[onSessionRevocation] bulk userId={}", e.userId());
      sessionArchivePort.recordRevocationForUser(e.userId(), e.reason(), e.revokedAt());
    } else {
      log.debug("[onSessionRevocation] sid={}", e.sessionId());
      sessionArchivePort.recordRevocation(e.sessionId(), e.reason(), e.revokedAt());
    }
  }
}
