package com.vandunxg.file_processing.auth.adapter.out.persistence.entity;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;

// Plain @Entity — does NOT extend AuditableEntity. The auth_email_verification_tokens table has
// no created_at/last_modified_at/created_by/last_modified_by/version columns (see migration
// V202607170906 and Global Constraints); adding those columns here would break ddl-auto=validate.
@Entity
@Table(name = "auth_email_verification_tokens")
@Getter
@Setter
@ToString
@RequiredArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(of = "id")
public class EmailVerificationTokenEntity {

  @Id
  @Column(name = "id")
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "token_hash", nullable = false, length = 64)
  private String tokenHash;

  @Column(name = "issued_at", nullable = false)
  private Instant issuedAt;

  @Column(name = "expires_at", nullable = false)
  private Instant expiresAt;

  @Column(name = "used_at")
  private Instant usedAt;

  @Column(name = "ip_address_hash", length = 64)
  private String ipAddressHash;
}
