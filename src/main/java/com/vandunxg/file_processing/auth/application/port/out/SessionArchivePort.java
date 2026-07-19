package com.vandunxg.file_processing.auth.application.port.out;

import java.time.Instant;
import java.util.UUID;

import com.vandunxg.file_processing.auth.domain.model.RevocationReason;
import com.vandunxg.file_processing.auth.domain.model.Session;

/**
 * Durable archive of every session for compliance and forensic queries. Redis remains the source of
 * truth for the "is this session currently active?" check; this port only records history.
 */
public interface SessionArchivePort {

  void save(Session session);

  void recordRotation(UUID sessionId, String newRefreshTokenHash, Instant lastUsedAt);

  void recordRevocation(UUID sessionId, RevocationReason reason, Instant revokedAt);

  void recordRevocationForUser(UUID userId, RevocationReason reason, Instant revokedAt);
}
