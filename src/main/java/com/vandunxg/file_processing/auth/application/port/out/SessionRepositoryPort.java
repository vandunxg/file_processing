package com.vandunxg.file_processing.auth.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.vandunxg.file_processing.auth.domain.model.RevocationReason;
import com.vandunxg.file_processing.auth.domain.model.Session;

/**
 * Redis-backed session store. This is the authoritative view of "is a session currently valid?".
 * Postgres is a downstream archive (see {@link SessionArchivePort}) reached asynchronously through
 * RabbitMQ.
 */
public interface SessionRepositoryPort {

  void save(Session session);

  Optional<Session> findActiveById(UUID sessionId, Instant now);

  Optional<UUID> resolveSessionIdByRefreshHash(String refreshHash);

  /** Returns {@code true} when the reuse-detection tombstone matched the caller's hash. */
  Optional<UUID> resolveReusedSessionIdByHash(String refreshHash);

  /**
   * Atomically swaps a session's current refresh hash. Returns {@code true} on success, {@code
   * false} when the old hash is no longer the active one (concurrent race / already rotated).
   */
  boolean rotateRefresh(
      UUID sessionId,
      String oldRefreshHash,
      String newRefreshHash,
      Instant lastUsedAt,
      Instant expiresAt);

  void revoke(UUID sessionId, RevocationReason reason, Instant now);

  int revokeAllForUser(UUID userId, RevocationReason reason, Instant now);

  List<Session> listActiveByUser(UUID userId, Instant now);
}
