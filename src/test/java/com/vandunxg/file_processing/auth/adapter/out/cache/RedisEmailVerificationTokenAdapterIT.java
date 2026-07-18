package com.vandunxg.file_processing.auth.adapter.out.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.vandunxg.file_processing.auth.configuration.AuthProperties;
import com.vandunxg.file_processing.auth.domain.model.EmailVerificationToken;
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

class RedisEmailVerificationTokenAdapterIT {

  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
          .withExposedPorts(6379)
          .withReuse(true);

  private static LettuceConnectionFactory connectionFactory;
  private static StringRedisTemplate stringRedisTemplate;
  private static RedisScript<Long> issueScript;
  private static RedisScript<Long> invalidateScript;
  private static RedisEmailVerificationTokenAdapter adapter;

  @BeforeAll
  static void startRedisAndAdapter() {
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

    AuthProperties authProperties =
        new AuthProperties(
            null,
            null,
            null,
            new AuthProperties.Redis(
                null,
                new AuthProperties.Redis.EmailVerificationKeys(
                    "it:email-verify:token:", "it:email-verify:user:")));
    adapter =
        new RedisEmailVerificationTokenAdapter(
            stringRedisTemplate, issueScript, invalidateScript, authProperties);
  }

  @AfterAll
  static void stopConnectionFactory() {
    connectionFactory.destroy();
  }

  @Test
  void save_thenFindByTokenHashForUpdate_roundTripsAndDeletesOnRead() {
    UUID userId = UUID.randomUUID();
    String tokenHash = "hash-" + System.nanoTime();
    Instant now = Instant.now();
    EmailVerificationToken token =
        EmailVerificationToken.issue(
            UUID.randomUUID(), userId, tokenHash, now, Duration.ofMinutes(30), "ip-hash-value");

    adapter.save(token);

    EmailVerificationToken found = adapter.findByTokenHashForUpdate(tokenHash).orElseThrow();
    assertThat(found.getId()).isEqualTo(token.getId());
    assertThat(found.getUserId()).isEqualTo(userId);
    assertThat(found.getIpAddressHash()).isEqualTo("ip-hash-value");

    // Second read must be empty — GETDEL already removed the key.
    assertThat(adapter.findByTokenHashForUpdate(tokenHash)).isEmpty();
  }

  @Test
  void findByTokenHashForUpdate_returnsEmpty_whenHashUnknown() {
    assertThat(adapter.findByTokenHashForUpdate("unknown-hash-" + System.nanoTime())).isEmpty();
  }

  @Test
  void invalidateAllForUser_removesTheCurrentToken() {
    UUID userId = UUID.randomUUID();
    String tokenHash = "hash-invalidate-" + System.nanoTime();
    Instant now = Instant.now();
    EmailVerificationToken token =
        EmailVerificationToken.issue(
            UUID.randomUUID(), userId, tokenHash, now, Duration.ofMinutes(30), null);
    adapter.save(token);

    adapter.invalidateAllForUser(userId, now);

    assertThat(adapter.findByTokenHashForUpdate(tokenHash)).isEmpty();
  }

  @Test
  void invalidateAllForUser_isNoOp_whenNoTokenExistsForUser() {
    UUID userId = UUID.randomUUID();

    adapter.invalidateAllForUser(userId, Instant.now());
    // No exception, nothing to assert beyond "did not throw".
  }
}
