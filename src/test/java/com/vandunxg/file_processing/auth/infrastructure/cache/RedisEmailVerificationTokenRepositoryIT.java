package com.vandunxg.file_processing.auth.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.vandunxg.file_processing.auth.application.AuthProperties;
import com.vandunxg.file_processing.auth.domain.model.EmailVerificationToken;
import com.vandunxg.file_processing.testsupport.AuthPropertiesFixture;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

class RedisEmailVerificationTokenRepositoryIT {

  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
          .withExposedPorts(6379)
          .withReuse(true);

  private static LettuceConnectionFactory connectionFactory;
  private static StringRedisTemplate stringRedisTemplate;
  private static RedisScript<Long> issueScript;
  private static RedisScript<Long> invalidateScript;
  private static RedisEmailVerificationTokenRepository repository;

  @BeforeAll
  static void startRedisAndRepository() {
    REDIS.start();
    connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
    connectionFactory.afterPropertiesSet();
    stringRedisTemplate = new StringRedisTemplate(connectionFactory);
    stringRedisTemplate.afterPropertiesSet();
    DefaultRedisScript<Long> issueRedisScript = new DefaultRedisScript<>();
    issueRedisScript.setLocation(new ClassPathResource("scripts/email-verification-issue.lua"));
    issueRedisScript.setResultType(Long.class);
    issueScript = issueRedisScript;
    DefaultRedisScript<Long> invalidateRedisScript = new DefaultRedisScript<>();
    invalidateRedisScript.setLocation(
        new ClassPathResource("scripts/email-verification-invalidate.lua"));
    invalidateRedisScript.setResultType(Long.class);
    invalidateScript = invalidateRedisScript;

    AuthProperties defaults = AuthPropertiesFixture.defaults();
    AuthProperties authProperties =
        new AuthProperties(
            defaults.password(),
            defaults.register(),
            defaults.login(),
            defaults.refresh(),
            defaults.session(),
            defaults.jwt(),
            defaults.emailVerification(),
            new AuthProperties.Redis(
                defaults.redis().throttle(),
                new AuthProperties.Redis.EmailVerificationKeys(
                    "it:email-verify:token:", "it:email-verify:user:"),
                defaults.redis().session(),
                defaults.redis().refresh(),
                defaults.redis().credentialVersion(),
                defaults.redis().userSessions()),
            defaults.amqp());
    repository =
        new RedisEmailVerificationTokenRepository(
            stringRedisTemplate,
            issueScript,
            invalidateScript,
            authProperties,
            new EmailVerificationTokenRedisMapperImpl());
  }

  @AfterAll
  static void stopConnectionFactory() {
    connectionFactory.destroy();
  }

  @Test
  void save_thenFindByTokenHashForUpdate_roundTripsAndDeletesOnRead() {
    UUID userId = UUID.randomUUID();
    String tokenHash = "a".repeat(64);
    Instant now = Instant.now();
    EmailVerificationToken token =
        EmailVerificationToken.issue(
            UUID.randomUUID(), userId, tokenHash, now, Duration.ofMinutes(30), "ip-hash-value");

    repository.save(token);

    EmailVerificationToken found = repository.findByTokenHashForUpdate(tokenHash).orElseThrow();
    assertThat(found.getId()).isEqualTo(token.getId());
    assertThat(found.getUserId()).isEqualTo(userId);
    assertThat(found.getIpAddressHash()).isEqualTo("ip-hash-value");

    // Second read must be empty — GETDEL already removed the key.
    assertThat(repository.findByTokenHashForUpdate(tokenHash)).isEmpty();
  }

  @Test
  void findByTokenHashForUpdate_returnsEmpty_whenHashUnknown() {
    assertThat(repository.findByTokenHashForUpdate("unknown-hash-" + System.nanoTime())).isEmpty();
  }

  @Test
  void invalidateAllForUser_removesTheCurrentToken() {
    UUID userId = UUID.randomUUID();
    String tokenHash = "b".repeat(64);
    Instant now = Instant.now();
    EmailVerificationToken token =
        EmailVerificationToken.issue(
            UUID.randomUUID(), userId, tokenHash, now, Duration.ofMinutes(30), null);
    repository.save(token);

    repository.invalidateAllForUser(userId, now);

    assertThat(repository.findByTokenHashForUpdate(tokenHash)).isEmpty();
  }

  @Test
  void invalidateAllForUser_isNoOp_whenNoTokenExistsForUser() {
    UUID userId = UUID.randomUUID();

    repository.invalidateAllForUser(userId, Instant.now());
    // No exception, nothing to assert beyond "did not throw".
  }
}
