package com.vandunxg.file_processing.auth.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.vandunxg.common.models.domain.AuditableDomain;
import com.vandunxg.file_processing.auth.domain.exception.AuthRule;
import com.vandunxg.file_processing.auth.domain.exception.AuthRuleViolation;
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
public class EmailVerificationToken extends AuditableDomain {

  private UUID id;
  private UUID userId;
  private String tokenHash;
  private Instant issuedAt;
  private Instant expiresAt;
  private Instant usedAt;
  private String ipAddressHash;

  public static EmailVerificationToken issue(
      UUID id, UUID userId, String tokenHash, Instant now, Duration ttl, String ipAddressHash) {
    if (id == null
        || userId == null
        || now == null
        || tokenHash == null
        || !tokenHash.matches("[0-9a-f]{64}")
        || (ipAddressHash != null && ipAddressHash.isBlank())
        || ttl == null
        || ttl.isZero()
        || ttl.isNegative()) {
      throw new IllegalArgumentException("Invalid email verification token issue request");
    }
    return EmailVerificationToken.builder()
        .id(id)
        .userId(userId)
        .tokenHash(tokenHash)
        .issuedAt(now)
        .expiresAt(now.plus(ttl))
        .ipAddressHash(ipAddressHash)
        .build();
  }

  public boolean isExpired(Instant now) {
    return !now.isBefore(expiresAt);
  }

  public boolean isUsableAt(Instant now) {
    return usedAt == null && !isExpired(now);
  }

  public void consume(Instant now) {
    if (!isUsableAt(now)) {
      throw new AuthRuleViolation(AuthRule.EMAIL_VERIFICATION_TOKEN_UNUSABLE);
    }
    this.usedAt = now;
  }
}
