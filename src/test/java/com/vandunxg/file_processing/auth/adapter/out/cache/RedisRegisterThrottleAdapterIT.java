package com.vandunxg.file_processing.auth.adapter.out.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import com.vandunxg.file_processing.auth.configuration.AuthProperties;
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

class RedisRegisterThrottleAdapterIT {

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
    connectionFactory =
        new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
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
    AuthProperties.Redis.Throttle throttleProperties =
        new AuthProperties.Redis.Throttle("it:allow-deny:", Duration.ofHours(1));
    RedisRegisterThrottleAdapter adapter =
        new RedisRegisterThrottleAdapter(
            stringRedisTemplate,
            script,
            new AuthProperties(null, null, null, new AuthProperties.Redis(throttleProperties)));

    String key = "ip-" + System.nanoTime();

    assertThat(adapter.tryConsume(key, 3)).isTrue();
    assertThat(adapter.tryConsume(key, 3)).isTrue();
    assertThat(adapter.tryConsume(key, 3)).isTrue();
    assertThat(adapter.tryConsume(key, 3)).isFalse();
  }

  @Test
  void tryConsume_isolatesDifferentKeys() {
    AuthProperties.Redis.Throttle throttleProperties =
        new AuthProperties.Redis.Throttle("it:isolate:", Duration.ofHours(1));
    RedisRegisterThrottleAdapter adapter =
        new RedisRegisterThrottleAdapter(
            stringRedisTemplate,
            script,
            new AuthProperties(null, null, null, new AuthProperties.Redis(throttleProperties)));

    String keyA = "ip-a-" + System.nanoTime();
    String keyB = "ip-b-" + System.nanoTime();

    assertThat(adapter.tryConsume(keyA, 1)).isTrue();
    assertThat(adapter.tryConsume(keyA, 1)).isFalse();
    assertThat(adapter.tryConsume(keyB, 1)).isTrue();
  }

  @Test
  void tryConsume_allowsAgain_afterTheWindowFullyDecays() throws InterruptedException {
    AuthProperties.Redis.Throttle throttleProperties =
        new AuthProperties.Redis.Throttle("it:decay:", Duration.ofSeconds(2));
    RedisRegisterThrottleAdapter adapter =
        new RedisRegisterThrottleAdapter(
            stringRedisTemplate,
            script,
            new AuthProperties(null, null, null, new AuthProperties.Redis(throttleProperties)));

    String key = "ip-decay-" + System.nanoTime();

    assertThat(adapter.tryConsume(key, 1)).isTrue();
    assertThat(adapter.tryConsume(key, 1)).isFalse();

    // Wait past two full 2s windows so the previous window's weighted contribution decays to
    // (near) zero — a real sleep is unavoidable here since the algorithm reads Redis server TIME.
    Thread.sleep(4500);

    assertThat(adapter.tryConsume(key, 1)).isTrue();
  }
}
