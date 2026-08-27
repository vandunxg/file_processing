package com.vandunxg.file_processing.auth.infrastructure.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import com.vandunxg.file_processing.auth.application.AuthProperties;
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

class RedisAuthThrottleIT {

  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
          .withExposedPorts(6379)
          .withReuse(true);

  private static LettuceConnectionFactory connectionFactory;
  private static StringRedisTemplate stringRedisTemplate;
  private static RedisScript<Long> script;

  @BeforeAll
  static void startRedisAndTemplate() {
    REDIS.start();
    connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
    connectionFactory.afterPropertiesSet();
    stringRedisTemplate = new StringRedisTemplate(connectionFactory);
    stringRedisTemplate.afterPropertiesSet();
    DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
    redisScript.setLocation(new ClassPathResource("scripts/sliding-window-rate-limiter.lua"));
    redisScript.setResultType(Long.class);
    script = redisScript;
  }

  @AfterAll
  static void stopConnectionFactory() {
    connectionFactory.destroy();
  }

  @Test
  void tryConsume_allowsUpToLimit_thenDeniesFurtherRequestsWithinTheSameWindow() {
    RedisAuthThrottle throttle = adapterWithPrefix("it:allow-deny:");

    String key = "ip-" + System.nanoTime();

    assertThat(throttle.tryConsume(key, 3, Duration.ofHours(1))).isTrue();
    assertThat(throttle.tryConsume(key, 3, Duration.ofHours(1))).isTrue();
    assertThat(throttle.tryConsume(key, 3, Duration.ofHours(1))).isTrue();
    assertThat(throttle.tryConsume(key, 3, Duration.ofHours(1))).isFalse();
  }

  @Test
  void tryConsume_isolatesDifferentKeys() {
    RedisAuthThrottle throttle = adapterWithPrefix("it:isolate:");

    String keyA = "ip-a-" + System.nanoTime();
    String keyB = "ip-b-" + System.nanoTime();

    assertThat(throttle.tryConsume(keyA, 1, Duration.ofHours(1))).isTrue();
    assertThat(throttle.tryConsume(keyA, 1, Duration.ofHours(1))).isFalse();
    assertThat(throttle.tryConsume(keyB, 1, Duration.ofHours(1))).isTrue();
  }

  @Test
  void tryConsume_allowsAgain_afterTheWindowFullyDecays() throws InterruptedException {
    RedisAuthThrottle throttle = adapterWithPrefix("it:decay:");

    String key = "ip-decay-" + System.nanoTime();
    Duration shortWindow = Duration.ofSeconds(2);

    assertThat(throttle.tryConsume(key, 1, shortWindow)).isTrue();
    assertThat(throttle.tryConsume(key, 1, shortWindow)).isFalse();

    // Wait past two full 2s windows so the previous window's weighted contribution decays to
    // (near) zero — a real sleep is unavoidable here since the algorithm reads Redis server TIME.
    Thread.sleep(4500);

    assertThat(throttle.tryConsume(key, 1, shortWindow)).isTrue();
  }

  private static RedisAuthThrottle adapterWithPrefix(String prefix) {
    AuthProperties defaults = AuthPropertiesFixture.defaults();
    AuthProperties withPrefix =
        new AuthProperties(
            defaults.password(),
            defaults.register(),
            defaults.login(),
            defaults.refresh(),
            defaults.session(),
            defaults.jwt(),
            defaults.emailVerification(),
            new AuthProperties.Redis(
                new AuthProperties.Redis.Throttle(prefix, Duration.ofHours(1)),
                defaults.redis().emailVerification(),
                defaults.redis().session(),
                defaults.redis().refresh(),
                defaults.redis().credentialVersion(),
                defaults.redis().userSessions()),
            defaults.amqp());
    return new RedisAuthThrottle(stringRedisTemplate, script, withPrefix);
  }
}
