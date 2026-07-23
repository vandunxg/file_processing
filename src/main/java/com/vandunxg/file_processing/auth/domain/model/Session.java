package com.vandunxg.file_processing.auth.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.vandunxg.common.models.domain.AuditableDomain;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Getter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Setter(AccessLevel.PRIVATE)
@EqualsAndHashCode(callSuper = false, of = "id")
public class Session extends AuditableDomain {

  private UUID id;
  private UUID userId;
  private int credentialVersion;
  private String userAgent;
  private String ipAddressHash;
  private Instant issuedAt;
  private Instant lastUsedAt;
  private Instant expiresAt;
  private Instant revokedAt;
  private RevocationReason revokedReason;
  private Instant deletedAt;
  private Long version;

  public static Session issue(
      UUID id,
      UUID userId,
      int credentialVersion,
      String userAgent,
      String ipAddressHash,
      Instant now,
      Duration ttl) {
    if (id == null
        || userId == null
        || now == null
        || (ipAddressHash != null && ipAddressHash.isBlank())
        || ttl == null
        || ttl.isZero()
        || ttl.isNegative()
        || credentialVersion < 1) {
      throw new IllegalArgumentException("Invalid session issue request");
    }
    return Session.builder()
        .id(id)
        .userId(userId)
        .credentialVersion(credentialVersion)
        .userAgent(userAgent)
        .ipAddressHash(ipAddressHash)
        .issuedAt(now)
        .lastUsedAt(now)
        .expiresAt(now.plus(ttl))
        .build();
  }

  public boolean isActive(Instant now) {
    return revokedAt == null && deletedAt == null && now.isBefore(expiresAt);
  }

  public void revoke(RevocationReason reason, Instant now) {
    if (reason == null || now == null) {
      throw new IllegalArgumentException("reason and now must not be null");
    }
    if (this.revokedAt != null) {
      return;
    }
    this.revokedAt = now;
    this.revokedReason = reason;
  }
}
