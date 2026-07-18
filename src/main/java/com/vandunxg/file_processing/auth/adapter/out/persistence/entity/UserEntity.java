package com.vandunxg.file_processing.auth.adapter.out.persistence.entity;

import java.time.Instant;
import java.util.UUID;

import com.vandunxg.common.models.entities.AuditableEntity;
import com.vandunxg.file_processing.auth.domain.model.UserStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;

@Entity
@Table(name = "auth_users")
@Getter
@Setter
@ToString
@RequiredArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false, of = "id")
public class UserEntity extends AuditableEntity {

  @Id
  @Column(name = "id")
  private UUID id;

  @Column(name = "username", nullable = false, length = 64)
  private String username;

  @Column(name = "normalized_username", nullable = false, length = 64)
  private String normalizedUsername;

  @Column(name = "email", nullable = false, length = 254)
  private String email;

  @Column(name = "normalized_email", nullable = false, length = 254)
  private String normalizedEmail;

  @Column(name = "display_name", nullable = false, length = 150)
  private String displayName;

  @Column(name = "password_hash", nullable = false, length = 255)
  private String passwordHash;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 20)
  private UserStatus status;

  @Column(name = "must_change_password", nullable = false)
  private boolean mustChangePassword;

  @Column(name = "failed_login_count", nullable = false)
  private int failedLoginCount;

  // domain doesn't track this field yet in this delivery
  @Column(name = "last_failed_login_at")
  private Instant lastFailedLoginAt;

  @Column(name = "locked_until")
  private Instant lockedUntil;

  @Column(name = "credential_version", nullable = false)
  private int credentialVersion;

  // domain doesn't track this field yet in this delivery
  @Column(name = "last_login_at")
  private Instant lastLoginAt;

  @Column(name = "password_changed_at", nullable = false)
  private Instant passwordChangedAt;

  @Column(name = "email_verified_at")
  private Instant emailVerifiedAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  @Version
  @Column(name = "version", nullable = false)
  private Long version;
}
