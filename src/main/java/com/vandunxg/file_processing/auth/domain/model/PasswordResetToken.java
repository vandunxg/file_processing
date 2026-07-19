package com.vandunxg.file_processing.auth.domain.model;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.vandunxg.file_processing.auth.domain.exception.AuthDomainException;
import com.vandunxg.file_processing.auth.domain.exception.AuthErrorCode;
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
@EqualsAndHashCode(of = "id")
public class PasswordResetToken {

  private UUID id;
  private UUID userId;
  private String tokenHash;
  private Instant issuedAt;
  private Instant expiresAt;
  private Instant usedAt;
  private String ipAddressHash;

  public static PasswordResetToken issue(
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
      throw new IllegalArgumentException("Invalid password reset token issue request");
    }
    return PasswordResetToken.builder()
        .id(id)
        .userId(userId)
        .tokenHash(tokenHash)
        .issuedAt(now)
        .expiresAt(now.plus(ttl))
        .ipAddressHash(ipAddressHash)
        .build();
  }

  public boolean isUsableAt(Instant now) {
    return usedAt == null && now.isBefore(expiresAt);
  }

  public void consume(Instant now) {
    if (!isUsableAt(now)) {
      throw new AuthDomainException(AuthErrorCode.PASSWORD_RESET_TOKEN_INVALID);
    }
    this.usedAt = now;
  }
}
