package com.vandunxg.file_processing.auth.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.vandunxg.file_processing.auth.domain.model.RevocationReason;
import com.vandunxg.file_processing.auth.domain.model.Session;

/** Store for refresh sessions and their token families. */
public interface SessionRepository {

  void save(Session session, String initialRefreshTokenHash);

  Optional<Session> findActiveById(UUID sessionId, Instant now);

  Optional<Session> findById(UUID sessionId);

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

  /**
   * Batch form of {@link #revokeAllForUser}. Callers pass a bounded batch of user ids; revoking a
   * role's worth of sessions one user at a time would keep a single transaction open across every
   * holder of that role.
   *
   * @return how many sessions were revoked
   */
  int revokeAllForUsers(Collection<UUID> userIds, RevocationReason reason, Instant now);

  List<Session> listActiveByUser(UUID userId, Instant now);

  int deleteExpiredOrRevoked(Instant now, int limit);
}
