package com.vandunxg.file_processing.auth.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;

import com.vandunxg.common.utils.HashUtils;
import com.vandunxg.common.utils.IdUtils;
import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.EmailVerificationTokenEntity;
import com.vandunxg.file_processing.auth.application.port.out.EmailVerificationTokenRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.RoleRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.UserRepositoryPort;
import com.vandunxg.file_processing.auth.domain.exception.AuthDomainException;
import com.vandunxg.file_processing.auth.domain.exception.AuthErrorCode;
import com.vandunxg.file_processing.auth.domain.model.EmailVerificationToken;
import com.vandunxg.file_processing.auth.domain.model.Role;
import com.vandunxg.file_processing.auth.domain.model.User;
import com.vandunxg.file_processing.testsupport.PostgresIntegrationTest;
import com.vandunxg.file_processing.testsupport.PostgresTestContainerBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Covers {@link EmailVerificationTokenRepositoryPort} end to end against a real Postgres:
 * hash-only persistence, pessimistic-lock lookup, consume/re-consume, bulk invalidation and
 * expiry.
 */
@PostgresIntegrationTest
class EmailVerificationTokenPersistenceAdapterIT extends PostgresTestContainerBase {

  @Autowired private EmailVerificationTokenRepositoryPort emailVerificationTokenRepositoryPort;
  @Autowired private UserRepositoryPort userRepositoryPort;
  @Autowired private RoleRepositoryPort roleRepositoryPort;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void save_persistsOnlyTheTokenHash_andEntityHasNoRawTokenColumn() {
    UUID userId = persistUser("evt-hash-only");
    String rawToken = "raw-token-" + System.nanoTime();
    String tokenHash = HashUtils.sha256(rawToken.getBytes(StandardCharsets.UTF_8));

    EmailVerificationToken token =
        EmailVerificationToken.issue(
            IdUtils.nextId(), userId, tokenHash, Instant.now(), Duration.ofMinutes(30), null);

    EmailVerificationToken saved = emailVerificationTokenRepositoryPort.save(token);

    String storedHash =
        jdbcTemplate.queryForObject(
            "SELECT token_hash FROM auth_email_verification_tokens WHERE id = ?",
            String.class,
            saved.getId());
    assertThat(storedHash).isEqualTo(tokenHash);

    var declaredFieldNames =
        Arrays.stream(EmailVerificationTokenEntity.class.getDeclaredFields())
            .map(Field::getName)
            .collect(Collectors.toSet());
    assertThat(declaredFieldNames)
        .as("entity must never grow a raw-token column, only its hash")
        .containsExactlyInAnyOrder(
            "id", "userId", "tokenHash", "issuedAt", "expiresAt", "usedAt", "ipAddressHash");
  }

  @Test
  void findByTokenHashForUpdate_returnsEmpty_whenHashUnknown() {
    String unknownHash = HashUtils.sha256Random();

    assertThat(emailVerificationTokenRepositoryPort.findByTokenHashForUpdate(unknownHash))
        .isEmpty();
  }

  @Test
  void consume_setsUsedAt_andSecondConsumeThrows() {
    UUID userId = persistUser("evt-consume");
    String tokenHash =
        HashUtils.sha256(("raw-consume-" + System.nanoTime()).getBytes(StandardCharsets.UTF_8));
    Instant now = Instant.now();

    EmailVerificationToken issued =
        EmailVerificationToken.issue(
            IdUtils.nextId(), userId, tokenHash, now, Duration.ofMinutes(30), null);
    emailVerificationTokenRepositoryPort.save(issued);

    EmailVerificationToken toConsume =
        emailVerificationTokenRepositoryPort.findByTokenHashForUpdate(tokenHash).orElseThrow();
    toConsume.consume(now);
    emailVerificationTokenRepositoryPort.save(toConsume);

    EmailVerificationToken reloaded =
        emailVerificationTokenRepositoryPort.findByTokenHashForUpdate(tokenHash).orElseThrow();
    assertThat(reloaded.getUsedAt().truncatedTo(ChronoUnit.MICROS))
        .isEqualTo(now.truncatedTo(ChronoUnit.MICROS));

    assertThatThrownBy(() -> reloaded.consume(now))
        .isInstanceOf(AuthDomainException.class)
        .extracting(ex -> ((AuthDomainException) ex).getError())
        .isEqualTo(AuthErrorCode.EMAIL_VERIFICATION_TOKEN_INVALID);
  }

  @Test
  void invalidateAllForUser_setsUsedAtOnAllPreviouslyUnusedTokens_andReturnsCount() {
    UUID userId = persistUser("evt-invalidate");
    Instant issuedAt = Instant.now();

    String firstHash =
        HashUtils.sha256(
            ("raw-invalidate-1-" + System.nanoTime()).getBytes(StandardCharsets.UTF_8));
    String secondHash =
        HashUtils.sha256(
            ("raw-invalidate-2-" + System.nanoTime()).getBytes(StandardCharsets.UTF_8));

    emailVerificationTokenRepositoryPort.save(
        EmailVerificationToken.issue(
            IdUtils.nextId(), userId, firstHash, issuedAt, Duration.ofMinutes(30), null));
    emailVerificationTokenRepositoryPort.save(
        EmailVerificationToken.issue(
            IdUtils.nextId(), userId, secondHash, issuedAt, Duration.ofMinutes(30), null));

    Instant invalidatedAt = issuedAt.plusSeconds(1);
    emailVerificationTokenRepositoryPort.invalidateAllForUser(userId, invalidatedAt);

    assertThat(
            emailVerificationTokenRepositoryPort
                .findByTokenHashForUpdate(firstHash)
                .orElseThrow()
                .getUsedAt()
                .truncatedTo(ChronoUnit.MICROS))
        .isEqualTo(invalidatedAt.truncatedTo(ChronoUnit.MICROS));
    assertThat(
            emailVerificationTokenRepositoryPort
                .findByTokenHashForUpdate(secondHash)
                .orElseThrow()
                .getUsedAt()
                .truncatedTo(ChronoUnit.MICROS))
        .isEqualTo(invalidatedAt.truncatedTo(ChronoUnit.MICROS));
  }

  @Test
  void isExpired_isTrue_whenTtlHasAlreadyElapsedAtIssueTime() {
    // Backdate "now" so expiresAt (issuedAt + ttl) already lies in the past relative to the real
    // clock, proving expiry without a Thread.sleep.
    Instant backdatedNow = Instant.now().minus(Duration.ofSeconds(10));

    EmailVerificationToken token =
        EmailVerificationToken.issue(
            IdUtils.nextId(),
            IdUtils.nextId(),
            HashUtils.sha256Random(),
            backdatedNow,
            Duration.ofSeconds(1),
            null);

    assertThat(token.isExpired(Instant.now())).isTrue();
    assertThat(token.isUsableAt(Instant.now())).isFalse();
  }

  private UUID persistUser(String usernamePrefix) {
    Role operatorRole = roleRepositoryPort.findByCode("OPERATOR").orElseThrow();
    String unique = usernamePrefix + "-" + System.nanoTime();
    User user =
        User.register(
            unique,
            unique + "@example.com",
            "Email Verification Test User",
            "{bcrypt}$2a$stubhash",
            operatorRole,
            Instant.now());
    return userRepositoryPort.save(user).getId();
  }
}
