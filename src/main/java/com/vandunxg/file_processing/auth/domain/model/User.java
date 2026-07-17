package com.vandunxg.file_processing.auth.domain.model;

import java.text.Normalizer;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.vandunxg.common.models.domain.AuditableDomain;
import com.vandunxg.common.utils.IdUtils;
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
@EqualsAndHashCode(callSuper = false, of = "id")
public class User extends AuditableDomain {

  private UUID id;
  private String username;
  private String normalizedUsername;
  private String email;
  private String normalizedEmail;
  private String displayName;
  private String passwordHash;
  private UserStatus status;
  private Set<Role> roles;
  private boolean mustChangePassword;
  private int failedLoginCount;
  private Instant lockedUntil;
  private int credentialVersion;
  private Instant passwordChangedAt;
  private Instant emailVerifiedAt;
  private Instant deletedAt;
  private Long version;

  public static User register(
      String username,
      String email,
      String displayName,
      String passwordHash,
      Role operatorRole,
      Instant now) {
    if (operatorRole == null
        || operatorRole.isDeleted()
        || !operatorRole.isActive()
        || !"OPERATOR".equals(operatorRole.getCode())) {
      throw new AuthDomainException(AuthErrorCode.INVALID_ROLE);
    }
    return User.builder()
        .id(IdUtils.nextId())
        .username(normalizeVisible(username))
        .normalizedUsername(normalize(username))
        .email(normalize(email))
        .normalizedEmail(normalize(email))
        .displayName(normalizeDisplayName(displayName))
        .passwordHash(passwordHash)
        .status(UserStatus.PENDING_VERIFY)
        .roles(Set.of(operatorRole))
        .mustChangePassword(false)
        .credentialVersion(1)
        .passwordChangedAt(now)
        .build();
  }

  public static String normalize(String value) {
    return normalizeVisible(value).toLowerCase(Locale.ROOT);
  }

  private static String normalizeVisible(String value) {
    return Normalizer.normalize(value, Normalizer.Form.NFKC).trim();
  }

  private static String normalizeDisplayName(String value) {
    return normalizeVisible(value).replaceAll("\\s+", " ");
  }

  public void verifyEmail(Instant now) {
    if (status != UserStatus.PENDING_VERIFY) {
      throw new AuthDomainException(AuthErrorCode.USER_ALREADY_VERIFIED);
    }
    this.status = UserStatus.ACTIVE;
    this.emailVerifiedAt = now;
  }

  public boolean isPendingVerify() {
    return status == UserStatus.PENDING_VERIFY;
  }

  public boolean isActive() {
    return status == UserStatus.ACTIVE && !isDeleted();
  }

  public boolean isDeleted() {
    return deletedAt != null;
  }

  public boolean isLocked() {
    return lockedUntil != null && lockedUntil.isAfter(Instant.now());
  }
}
