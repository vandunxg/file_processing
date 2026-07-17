package com.vandunxg.file_processing.auth.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import com.vandunxg.file_processing.testsupport.PostgresIntegrationTest;
import com.vandunxg.file_processing.testsupport.PostgresTestContainerBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Verifies the 7 Flyway migrations (V202607170900 - V202607170906) apply cleanly against a real
 * Postgres instance and that the default role / permission seed data lands as expected.
 *
 * <p>Task 2 introduces {@code EmailVerificationTokenEntity}; until then this file only asserts that
 * its backing table exists (via a raw JDBC round-trip), not that the entity round-trips.
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
}
