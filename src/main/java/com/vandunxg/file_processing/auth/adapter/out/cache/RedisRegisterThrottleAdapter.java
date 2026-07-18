package com.vandunxg.file_processing.auth.adapter.out.cache;

import java.util.List;

import com.vandunxg.file_processing.auth.application.port.out.RegisterThrottlePort;
import com.vandunxg.file_processing.auth.configuration.AuthProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Cluster-wide, atomic sliding-window-counter rate limiter. Replaces {@code
 * CaffeineRegisterThrottleAdapter}, which was per-instance only.
 */
@Component
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-THROTTLE-REDIS")
public class RedisRegisterThrottleAdapter implements RegisterThrottlePort {

  private final StringRedisTemplate stringRedisTemplate;
  private final RedisScript<Long> slidingWindowRateLimiterScript;
  private final AuthProperties authProperties;

  @Override
  public boolean tryConsume(String key, int maxPerHour) {
    String redisKey = authProperties.redis().throttle().keyPrefix() + key;
    long windowSeconds = authProperties.redis().throttle().window().getSeconds();

    Long allowed =
        stringRedisTemplate.execute(
            slidingWindowRateLimiterScript,
            List.of(redisKey),
            String.valueOf(maxPerHour),
            String.valueOf(windowSeconds));

    boolean result = allowed != null && allowed == 1L;
    log.debug(
        "[tryConsume] evaluated rate limit key={} maxPerHour={} allowed={}",
        key,
        maxPerHour,
        result);
    return result;
  }
}
