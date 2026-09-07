package com.vandunxg.file_processing.auth.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.vandunxg.file_processing.auth.domain.model.PasswordResetToken;
import com.vandunxg.file_processing.testsupport.AuthIntegrationTestBase;
import com.vandunxg.file_processing.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@PostgresIntegrationTest
class JpaPasswordResetTokenRepositoryIT extends AuthIntegrationTestBase {

  @Autowired private JpaPasswordResetTokenRepository jpaPasswordResetTokenRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  @Transactional
  void saveFindForUpdateAndInvalidateAllForUserUsePersistentTokens() {
    UUID userId = UUID.randomUUID();
    Instant now = Instant.parse("2026-07-20T10:15:30Z");
    createUser(userId, now);
    PasswordResetToken first = token(userId, "a".repeat(64), now);
    PasswordResetToken second = token(userId, "b".repeat(64), now);

    jpaPasswordResetTokenRepository.save(first);
    jpaPasswordResetTokenRepository.save(second);

    assertThat(jpaPasswordResetTokenRepository.findByTokenHashForUpdate(first.getTokenHash()))
        .contains(first);

    jpaPasswordResetTokenRepository.invalidateAllForUser(userId, now.plusSeconds(1));

    assertThat(jpaPasswordResetTokenRepository.findByTokenHashForUpdate(first.getTokenHash()))
        .get()
        .extracting(PasswordResetToken::getUsedAt)
        .isEqualTo(now.plusSeconds(1));
    assertThat(jpaPasswordResetTokenRepository.findByTokenHashForUpdate(second.getTokenHash()))
        .get()
        .extracting(PasswordResetToken::getUsedAt)
        .isEqualTo(now.plusSeconds(1));
  }

  @Test
  @Transactional
  void deletesExpiredTokensInBoundedBatches() {
    UUID userId = UUID.randomUUID();
    Instant now = Instant.parse("2026-07-20T10:15:30Z");
    createUser(userId, now);
    PasswordResetToken firstExpired =
        token(userId, "c".repeat(64), now.minus(Duration.ofMinutes(30)));
    PasswordResetToken secondExpired =
        token(userId, "d".repeat(64), now.minus(Duration.ofMinutes(31)));
    PasswordResetToken active = token(userId, "e".repeat(64), now);
    jpaPasswordResetTokenRepository.save(firstExpired);
    jpaPasswordResetTokenRepository.save(secondExpired);
    jpaPasswordResetTokenRepository.save(active);

    assertThat(jpaPasswordResetTokenRepository.deleteExpired(now, 1)).isEqualTo(1);
    assertThat(
            java.util.stream.Stream.of(firstExpired, secondExpired)
                .filter(
                    token ->
                        jpaPasswordResetTokenRepository
                            .findByTokenHashForUpdate(token.getTokenHash())
                            .isPresent()))
        .singleElement()
        .isIn(firstExpired, secondExpired);
    assertThat(jpaPasswordResetTokenRepository.findByTokenHashForUpdate(active.getTokenHash()))
        .contains(active);
    assertThat(jpaPasswordResetTokenRepository.deleteExpired(now, 10)).isEqualTo(1);
    assertThat(
            jpaPasswordResetTokenRepository.findByTokenHashForUpdate(firstExpired.getTokenHash()))
        .isEmpty();
    assertThat(
            jpaPasswordResetTokenRepository.findByTokenHashForUpdate(secondExpired.getTokenHash()))
        .isEmpty();
    assertThat(jpaPasswordResetTokenRepository.findByTokenHashForUpdate(active.getTokenHash()))
        .contains(active);
    assertThat(jpaPasswordResetTokenRepository.deleteExpired(now, 10)).isZero();
  }

  private static PasswordResetToken token(UUID userId, String tokenHash, Instant now) {
    return PasswordResetToken.issue(
        UUID.randomUUID(), userId, tokenHash, now, Duration.ofMinutes(15), null);
  }

  private void createUser(UUID userId, Instant now) {
    jdbcTemplate.update(
        "INSERT INTO auth_users (id, username, normalized_username, email, normalized_email, "
            + "display_name, password_hash, status, password_changed_at, created_at, "
            + "last_modified_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        userId,
        "reset-token-user-" + userId,
        "reset-token-user-" + userId,
        "reset-token-user-" + userId + "@example.com",
        "reset-token-user-" + userId + "@example.com",
        "Reset Token User",
        "{bcrypt}$2a$stubhash",
        "ACTIVE",
        Timestamp.from(now),
        Timestamp.from(now),
        Timestamp.from(now));
  }
}
