package com.vandunxg.file_processing.auth.domain.model;

import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import com.vandunxg.common.models.domain.AuditableDomain;
import com.vandunxg.common.utils.IdUtils;
import com.vandunxg.file_processing.auth.domain.exception.AuthDomainException;
import com.vandunxg.file_processing.auth.domain.exception.AuthErrorCode;
import lombok.*;
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
  @Builder.Default private Set<Role> roles = Set.of();
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

  public static User bootstrapAdmin(
      String username,
      String email,
      String displayName,
      String passwordHash,
      Role adminRole,
      Instant now) {
    if (adminRole == null
        || adminRole.isDeleted()
        || !adminRole.isActive()
        || !"ADMIN".equals(adminRole.getCode())) {
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
        .status(UserStatus.ACTIVE)
        .roles(Set.of(adminRole))
        .mustChangePassword(true)
        .credentialVersion(1)
        .passwordChangedAt(now)
        .emailVerifiedAt(now)
        .build();
  }

  public static User adminCreate(
      String username,
      String email,
      String displayName,
      String passwordHash,
      Set<Role> roles,
      boolean autoVerifyEmail,
      Instant now) {
    if (passwordHash == null
        || passwordHash.isBlank()
        || roles == null
        || roles.isEmpty()
        || now == null) {
      throw new IllegalArgumentException("A user requires a password, role, and creation time");
    }
    if (roles.stream().anyMatch(role -> role == null || !role.isActive())) {
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
        .roles(Set.copyOf(roles))
        .status(autoVerifyEmail ? UserStatus.ACTIVE : UserStatus.PENDING_VERIFY)
        .mustChangePassword(true)
        .credentialVersion(1)
        .passwordChangedAt(now)
        .emailVerifiedAt(autoVerifyEmail ? now : null)
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

  public boolean isLocked(Instant now) {
    return lockedUntil != null && lockedUntil.isAfter(now);
  }

  public void registerFailedLogin(Instant now, int maxFailures, Duration lockDuration) {
    if (now == null || lockDuration == null || maxFailures < 1) {
      throw new IllegalArgumentException("Invalid failed-login parameters");
    }
    if (lockedUntil != null && !now.isBefore(lockedUntil)) {
      // Prior to lock window elapsed — start a new counter cycle.
      this.failedLoginCount = 0;
      this.lockedUntil = null;
    }
    this.failedLoginCount += 1;
    if (this.failedLoginCount >= maxFailures) {
      this.lockedUntil = now.plus(lockDuration);
    }
  }

  public void resetFailedLogin() {
    this.failedLoginCount = 0;
    this.lockedUntil = null;
  }

  public void bumpCredentialVersion(Instant now) {
    if (now == null) {
      throw new IllegalArgumentException("now must not be null");
    }
    this.credentialVersion += 1;
    this.passwordChangedAt = now;
  }

  public void resetPassword(String passwordHash, Instant now) {
    if (passwordHash == null
        || passwordHash.isBlank()
        || now == null
        || (!isActive() && !isPendingVerify())) {
      throw new AuthDomainException(AuthErrorCode.PASSWORD_RESET_NOT_ALLOWED);
    }
    this.passwordHash = passwordHash;
    this.credentialVersion += 1;
    this.passwordChangedAt = now;
    this.failedLoginCount = 0;
    this.lockedUntil = null;
    this.mustChangePassword = false;
    if (isPendingVerify()) {
      this.status = UserStatus.ACTIVE;
      this.emailVerifiedAt = now;
    }
  }

  public void changePassword(String passwordHash, Instant now) {
    if (passwordHash == null || passwordHash.isBlank() || now == null) {
      throw new IllegalArgumentException("Invalid password change parameters");
    }
    this.passwordHash = passwordHash;
    this.mustChangePassword = false;
    bumpCredentialVersion(now);
  }

  public void updateByAdmin(String email, String displayName, Set<Role> roles) {
    if (email == null || displayName == null || roles == null || roles.isEmpty()) {
      throw new IllegalArgumentException("Email, display name, and at least one role are required");
    }
    if (roles.stream().anyMatch(role -> role == null || !role.isActive())) {
      throw new AuthDomainException(AuthErrorCode.INVALID_ROLE);
    }
    this.email = normalize(email);
    this.normalizedEmail = normalize(email);
    this.displayName = normalizeDisplayName(displayName);
    this.roles = Set.copyOf(roles);
  }

  public void disable() {
    if (isDeleted()) {
      throw new AuthDomainException(AuthErrorCode.USER_NOT_FOUND);
    }
    this.status = UserStatus.DISABLED;
  }

  public void enable() {
    if (status == UserStatus.DISABLED) {
      this.status = UserStatus.ACTIVE;
    }
  }

  public void unlock() {
    resetFailedLogin();
  }

  public void resetTemporaryPassword(String passwordHash, Instant now) {
    if (passwordHash == null || passwordHash.isBlank() || now == null || isDeleted()) {
      throw new IllegalArgumentException("Invalid temporary password reset");
    }
    this.passwordHash = passwordHash;
    this.mustChangePassword = true;
    this.credentialVersion += 1;
    this.passwordChangedAt = now;
    resetFailedLogin();
  }

  public void invalidateCredentials() {
    this.credentialVersion += 1;
  }

  /** Reconstitutes roles after a persistence load; only the repository adapter should call this. */
  public void enrichRoles(Set<Role> roles) {
    this.roles = roles == null ? Set.of() : Set.copyOf(roles);
  }
}
