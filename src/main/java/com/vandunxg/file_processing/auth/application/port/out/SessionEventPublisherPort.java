package com.vandunxg.file_processing.auth.application.port.out;

import java.time.Instant;
import java.util.UUID;

import com.vandunxg.file_processing.auth.domain.model.RevocationReason;
import com.vandunxg.file_processing.auth.domain.model.Session;

public interface SessionEventPublisherPort {

  void publishPersist(Session session);

  void publishRotation(UUID sessionId, String newRefreshTokenHash, Instant lastUsedAt);

  void publishRevocation(UUID sessionId, RevocationReason reason, Instant revokedAt);

  void publishRevocationForUser(UUID userId, RevocationReason reason, Instant revokedAt);
}
