package com.vandunxg.file_processing.auth.domain.event;

import java.time.Instant;
import java.util.UUID;

import com.vandunxg.file_processing.auth.domain.model.RevocationReason;

public record SessionRevocationEvent(
    UUID sessionId, UUID userId, RevocationReason reason, Instant revokedAt) {

  /** Bulk revoke of every session belonging to {@code userId}. */
  public static SessionRevocationEvent forUser(
      UUID userId, RevocationReason reason, Instant revokedAt) {
    return new SessionRevocationEvent(null, userId, reason, revokedAt);
  }

  public boolean isBulkForUser() {
    return sessionId == null;
  }
}
