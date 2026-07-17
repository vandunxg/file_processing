package com.vandunxg.file_processing.auth.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.vandunxg.file_processing.testsupport.PostgresIntegrationTest;
import com.vandunxg.file_processing.testsupport.PostgresTestContainerBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifies the 7 Flyway migrations (V202607170900 - V202607170906) apply cleanly against a real
 * Postgres instance and that the default role / permission seed data lands as expected.
 *
 * <p>Task 2 owns {@code EmailVerificationTokenEntity}; the entity round-trip itself is covered by
 * {@link EmailVerificationTokenPersistenceAdapterIT}. This file only proves the migration's own
 * DDL — table presence and the {@code UNIQUE(token_hash)} constraint — via raw JDBC.
 */
@PostgresIntegrationTest
class MigrationAndSeedIT extends PostgresTestContainerBase {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void migrations_seedExactlyTwoRoles_adminAndOperatorBothConst() {
    Integer roleCount = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM role", Integer.class);
    assertThat(roleCount).isEqualTo(2);

    List<Map<String, Object>> roles =
        jdbcTemplate.queryForList("SELECT code, is_const FROM role ORDER BY code");

    assertThat(roles).hasSize(2);
    assertThat(roles.get(0)).containsEntry("code", "ADMIN").containsEntry("is_const", true);
    assertThat(roles.get(1)).containsEntry("code", "OPERATOR").containsEntry("is_const", true);
  }

  @Test
  void migrations_seedRolePermissions_adminHasAllManage_operatorHasTen() {
    Integer adminCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM role_permission rp JOIN role r ON r.id = rp.role_id"
                + " WHERE r.code = 'ADMIN'",
            Integer.class);
    assertThat(adminCount).isEqualTo(1);

    Integer adminAllManageCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM role_permission rp JOIN role r ON r.id = rp.role_id"
                + " WHERE r.code = 'ADMIN' AND rp.resource_code = 'ALL' AND rp.action = 'MANAGE'",
            Integer.class);
    assertThat(adminAllManageCount).isEqualTo(1);

    Integer operatorCount =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM role_permission rp JOIN role r ON r.id = rp.role_id"
                + " WHERE r.code = 'OPERATOR'",
            Integer.class);
    assertThat(operatorCount).isEqualTo(10);
  }

  @Test
  void migrations_createEmailVerificationTokensTable() {
    // Task 2 owns the JPA entity for this table; here we only assert the migration created it.
    Integer count =
        jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM auth_email_verification_tokens", Integer.class);
    assertThat(count).isEqualTo(0);
  }

  @Test
  void migrations_enforceUniqueTokenHash_onAuthEmailVerificationTokens() {
    UUID userId = UUID.randomUUID();
    Instant now = Instant.now();
    jdbcTemplate.update(
        "INSERT INTO auth_users (id, username, normalized_username, email, normalized_email, "
            + "display_name, password_hash, status, password_changed_at, created_at,"
            + " last_modified_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        userId,
        "migration-it-unique-token",
        "migration-it-unique-token",
        "migration-it-unique-token@example.com",
        "migration-it-unique-token@example.com",
        "Migration IT Unique Token User",
        "{bcrypt}$2a$stubhash",
        "PENDING_VERIFY",
        Timestamp.from(now),
        Timestamp.from(now),
        Timestamp.from(now));

    String sharedTokenHash = "a".repeat(64);
    jdbcTemplate.update(
        "INSERT INTO auth_email_verification_tokens (id, user_id, token_hash, issued_at,"
            + " expires_at) VALUES (?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        userId,
        sharedTokenHash,
        Timestamp.from(now),
        Timestamp.from(now.plusSeconds(3600)));

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "INSERT INTO auth_email_verification_tokens (id, user_id, token_hash,"
                        + " issued_at, expires_at) VALUES (?, ?, ?, ?, ?)",
                    UUID.randomUUID(),
                    userId,
                    sharedTokenHash,
                    Timestamp.from(now),
                    Timestamp.from(now.plusSeconds(3600))))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
}
