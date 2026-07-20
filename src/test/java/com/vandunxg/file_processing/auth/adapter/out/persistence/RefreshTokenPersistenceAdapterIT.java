package com.vandunxg.file_processing.auth.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.vandunxg.file_processing.auth.application.port.out.RoleRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.SessionRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.UserRepositoryPort;
import com.vandunxg.file_processing.auth.domain.model.RevocationReason;
import com.vandunxg.file_processing.auth.domain.model.Role;
import com.vandunxg.file_processing.auth.domain.model.Session;
import com.vandunxg.file_processing.auth.domain.model.User;
import com.vandunxg.file_processing.testsupport.AuthIntegrationTestBase;
import com.vandunxg.file_processing.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@PostgresIntegrationTest
class RefreshTokenPersistenceAdapterIT extends AuthIntegrationTestBase {

  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private RoleRepositoryPort roleRepositoryPort;
  @Autowired private SessionRepositoryPort sessionRepositoryPort;
  @Autowired private UserRepositoryPort userRepositoryPort;

  @Test
  void createsDurableRefreshSessionAndTokenTables() {
    Boolean refreshSessionsExists =
        jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
              SELECT 1 FROM information_schema.tables
              WHERE table_schema = 'public' AND table_name = 'auth_refresh_sessions'
            )
            """,
            Boolean.class);
    Boolean refreshTokensExists =
        jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
              SELECT 1 FROM information_schema.tables
              WHERE table_schema = 'public' AND table_name = 'auth_refresh_tokens'
            )
            """,
            Boolean.class);

    assertThat(refreshSessionsExists).isTrue();
    assertThat(refreshTokensExists).isTrue();
  }

  @Test
  void savesTheInitialRefreshTokenInTheDurableFamily() {
    Instant now = Instant.now();
    Role operator = roleRepositoryPort.findByCode("OPERATOR").orElseThrow();
    User user =
        userRepositoryPort.save(
            User.register(
                "refresh-family-" + System.nanoTime(),
                "refresh-family-" + System.nanoTime() + "@example.com",
                "Refresh Family",
                "{bcrypt}$2a$stubhash",
                operator,
                now));
    Session session =
        Session.issue(
            UUID.randomUUID(),
            user.getId(),
            user.getCredentialVersion(),
            "JUnit",
            null,
            now,
            Duration.ofDays(7));

    sessionRepositoryPort.save(session, "a".repeat(64));

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM auth_refresh_sessions WHERE id = ?",
                Integer.class,
                session.getId()))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM auth_refresh_tokens WHERE session_id = ? AND token_hash = ?",
                Integer.class,
                session.getId(),
                "a".repeat(64)))
        .isEqualTo(1);
  }

  @Test
  void concurrentRotationConsumesOneTokenAndCreatesOneChild() throws Exception {
    Instant now = Instant.now();
    Role operator = roleRepositoryPort.findByCode("OPERATOR").orElseThrow();
    User user =
        userRepositoryPort.save(
            User.register(
                "refresh-race-" + System.nanoTime(),
                "refresh-race-" + System.nanoTime() + "@example.com",
                "Refresh Race",
                "{bcrypt}$2a$stubhash",
                operator,
                now));
    String oldHash = "c".repeat(64);
    Session session =
        Session.issue(
            UUID.randomUUID(),
            user.getId(),
            user.getCredentialVersion(),
            "JUnit",
            null,
            now,
            Duration.ofDays(7));
    sessionRepositoryPort.save(session, oldHash);

    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      Future<Boolean> first =
          executor.submit(() -> rotateWhenReady(session, oldHash, "d".repeat(64), ready, start));
      Future<Boolean> second =
          executor.submit(() -> rotateWhenReady(session, oldHash, "e".repeat(64), ready, start));

      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      assertThat(first.get(10, TimeUnit.SECONDS) ^ second.get(10, TimeUnit.SECONDS)).isTrue();
    } finally {
      executor.shutdown();
    }

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM auth_refresh_tokens WHERE session_id = ?",
                Integer.class,
                session.getId()))
        .isEqualTo(2);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM auth_refresh_tokens WHERE session_id = ? AND consumed_at IS NOT NULL",
                Integer.class,
                session.getId()))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM auth_refresh_tokens WHERE session_id = ? AND parent_token_id IS NOT NULL",
                Integer.class,
                session.getId()))
        .isEqualTo(1);

    sessionRepositoryPort.revoke(session.getId(), RevocationReason.TOKEN_REUSE, Instant.now());

    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM auth_refresh_tokens WHERE session_id = ? AND revoked_at IS NOT NULL",
                Integer.class,
                session.getId()))
        .isEqualTo(2);
  }

  @Test
  void deletesExpiredAndRevokedFamiliesButKeepsActiveSessions() {
    Instant now = Instant.parse("2026-07-20T10:15:30Z");
    Role operator = roleRepositoryPort.findByCode("OPERATOR").orElseThrow();
    User user =
        userRepositoryPort.save(
            User.register(
                "refresh-cleanup-" + System.nanoTime(),
                "refresh-cleanup-" + System.nanoTime() + "@example.com",
                "Refresh Cleanup",
                "{bcrypt}$2a$stubhash",
                operator,
                now));
    Session expired =
        Session.issue(
            UUID.randomUUID(),
            user.getId(),
            user.getCredentialVersion(),
            "JUnit",
            null,
            now.minus(Duration.ofDays(2)),
            Duration.ofDays(1));
    Session revoked =
        Session.issue(
            UUID.randomUUID(),
            user.getId(),
            user.getCredentialVersion(),
            "JUnit",
            null,
            now,
            Duration.ofDays(1));
    revoked.revoke(RevocationReason.LOGOUT, now);
    Session active =
        Session.issue(
            UUID.randomUUID(),
            user.getId(),
            user.getCredentialVersion(),
            "JUnit",
            null,
            now,
            Duration.ofDays(1));
    sessionRepositoryPort.save(expired, "f".repeat(64));
    sessionRepositoryPort.save(revoked, "a".repeat(64));
    sessionRepositoryPort.save(active, "b".repeat(64));

    assertThat(sessionRepositoryPort.deleteExpiredOrRevoked(now, 10)).isEqualTo(2);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM auth_refresh_sessions WHERE id = ?",
                Integer.class,
                active.getId()))
        .isEqualTo(1);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM auth_refresh_tokens WHERE session_id IN (?, ?)",
                Integer.class,
                expired.getId(),
                revoked.getId()))
        .isZero();
    assertThat(sessionRepositoryPort.deleteExpiredOrRevoked(now, 10)).isZero();
  }

  private boolean rotateWhenReady(
      Session session, String oldHash, String newHash, CountDownLatch ready, CountDownLatch start)
      throws InterruptedException {
    ready.countDown();
    start.await();
    return sessionRepositoryPort.rotateRefresh(
        session.getId(), oldHash, newHash, Instant.now(), session.getExpiresAt());
  }
}
