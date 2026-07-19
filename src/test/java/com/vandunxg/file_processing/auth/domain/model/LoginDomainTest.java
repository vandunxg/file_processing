package com.vandunxg.file_processing.auth.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class LoginDomainTest {

  private static final Instant NOW = Instant.parse("2026-07-19T10:15:30Z");
  private static final Duration LOCK_DURATION = Duration.ofMinutes(15);
  private static final int MAX_FAILURES = 5;
  private static final String REFRESH_HASH = "a".repeat(64);

  @Test
  void registerFailedLoginIncrementsCounterUntilThresholdThenLocks() {
    User user = activeUser();

    for (int i = 1; i < MAX_FAILURES; i++) {
      user.registerFailedLogin(NOW, MAX_FAILURES, LOCK_DURATION);
      assertThat(user.getFailedLoginCount()).isEqualTo(i);
      assertThat(user.getLockedUntil()).isNull();
      assertThat(user.isLocked(NOW)).isFalse();
    }
    user.registerFailedLogin(NOW, MAX_FAILURES, LOCK_DURATION);
    assertThat(user.getFailedLoginCount()).isEqualTo(MAX_FAILURES);
    assertThat(user.getLockedUntil()).isEqualTo(NOW.plus(LOCK_DURATION));
    assertThat(user.isLocked(NOW)).isTrue();
    assertThat(user.isLocked(NOW.plus(LOCK_DURATION).minusSeconds(1))).isTrue();
    assertThat(user.isLocked(NOW.plus(LOCK_DURATION))).isFalse();
  }

  @Test
  void registerFailedLoginAfterLockExpiryStartsANewCycle() {
    User user = activeUser();
    for (int i = 0; i < MAX_FAILURES; i++) {
      user.registerFailedLogin(NOW, MAX_FAILURES, LOCK_DURATION);
    }
    Instant afterLock = NOW.plus(LOCK_DURATION).plusSeconds(1);

    user.registerFailedLogin(afterLock, MAX_FAILURES, LOCK_DURATION);

    assertThat(user.getFailedLoginCount()).isEqualTo(1);
    assertThat(user.getLockedUntil()).isNull();
    assertThat(user.isLocked(afterLock)).isFalse();
  }

  @Test
  void resetFailedLoginClearsCounterAndLock() {
    User user = activeUser();
    for (int i = 0; i < MAX_FAILURES; i++) {
      user.registerFailedLogin(NOW, MAX_FAILURES, LOCK_DURATION);
    }

    user.resetFailedLogin();

    assertThat(user.getFailedLoginCount()).isZero();
    assertThat(user.getLockedUntil()).isNull();
    assertThat(user.isLocked(NOW)).isFalse();
  }

  @Test
  void bumpCredentialVersionIncrementsAndStampsPasswordChangedAt() {
    User user = activeUser();
    int before = user.getCredentialVersion();

    user.bumpCredentialVersion(NOW);

    assertThat(user.getCredentialVersion()).isEqualTo(before + 1);
    assertThat(user.getPasswordChangedAt()).isEqualTo(NOW);
  }

  @Test
  void sessionIssueSetsAllInvariants() {
    UUID id = UUID.randomUUID();
    UUID userId = UUID.randomUUID();

    Session session =
        Session.issue(
            id, userId, 1, REFRESH_HASH, "curl/8", "ip-hash".repeat(9), NOW, Duration.ofDays(7));

    assertThat(session.getId()).isEqualTo(id);
    assertThat(session.getUserId()).isEqualTo(userId);
    assertThat(session.getCredentialVersion()).isEqualTo(1);
    assertThat(session.getRefreshTokenHash()).isEqualTo(REFRESH_HASH);
    assertThat(session.getIssuedAt()).isEqualTo(NOW);
    assertThat(session.getLastUsedAt()).isEqualTo(NOW);
    assertThat(session.getExpiresAt()).isEqualTo(NOW.plus(Duration.ofDays(7)));
    assertThat(session.getRevokedAt()).isNull();
    assertThat(session.isActive(NOW)).isTrue();
    assertThat(session.isActive(NOW.plus(Duration.ofDays(7)))).isFalse();
  }

  @Test
  void sessionIssueRejectsInvalidInputs() {
    UUID id = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    Duration ttl = Duration.ofDays(7);

    assertThatThrownBy(() -> Session.issue(null, userId, 1, REFRESH_HASH, "ua", null, NOW, ttl))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Session.issue(id, null, 1, REFRESH_HASH, "ua", null, NOW, ttl))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Session.issue(id, userId, 0, REFRESH_HASH, "ua", null, NOW, ttl))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Session.issue(id, userId, 1, "raw-token", "ua", null, NOW, ttl))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Session.issue(id, userId, 1, "A".repeat(64), "ua", null, NOW, ttl))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> Session.issue(id, userId, 1, REFRESH_HASH, "ua", " ", NOW, ttl))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> Session.issue(id, userId, 1, REFRESH_HASH, "ua", null, NOW, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rotateRefreshUpdatesHashAndLastUsedAt() {
    Session session = defaultSession();
    String newHash = "b".repeat(64);

    session.rotateRefresh(newHash, NOW.plusSeconds(60));

    assertThat(session.getRefreshTokenHash()).isEqualTo(newHash);
    assertThat(session.getLastUsedAt()).isEqualTo(NOW.plusSeconds(60));
  }

  @Test
  void rotateRefreshRejectsRevokedOrExpiredSession() {
    Session revoked = defaultSession();
    revoked.revoke(RevocationReason.LOGOUT, NOW.plusSeconds(1));
    assertThatThrownBy(() -> revoked.rotateRefresh("b".repeat(64), NOW.plusSeconds(2)))
        .isInstanceOf(IllegalStateException.class);

    Session expired = defaultSession();
    assertThatThrownBy(() -> expired.rotateRefresh("b".repeat(64), NOW.plus(Duration.ofDays(7))))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void revokeIsIdempotentAndKeepsFirstReason() {
    Session session = defaultSession();

    session.revoke(RevocationReason.LOGOUT, NOW.plusSeconds(1));
    session.revoke(RevocationReason.PASSWORD_CHANGED, NOW.plusSeconds(2));

    assertThat(session.getRevokedAt()).isEqualTo(NOW.plusSeconds(1));
    assertThat(session.getRevokedReason()).isEqualTo(RevocationReason.LOGOUT);
    assertThat(session.isActive(NOW.plusSeconds(3))).isFalse();
  }

  private static Session defaultSession() {
    return Session.issue(
        UUID.randomUUID(),
        UUID.randomUUID(),
        1,
        REFRESH_HASH,
        "user-agent",
        null,
        NOW,
        Duration.ofDays(7));
  }

  private static User activeUser() {
    Role operator =
        Role.builder().id(UUID.randomUUID()).code("OPERATOR").status(ActiveStatus.ACTIVE).build();
    User u = User.register("op", "op@example.com", "Op", "hash", operator, NOW);
    u.verifyEmail(NOW);
    return u;
  }
}
