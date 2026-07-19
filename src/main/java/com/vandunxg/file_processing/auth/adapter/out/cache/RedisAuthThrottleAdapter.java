package com.vandunxg.file_processing.auth.adapter.out.cache;

import java.time.Duration;
import java.util.List;

import com.vandunxg.file_processing.auth.application.port.out.AuthThrottlePort;
import com.vandunxg.file_processing.auth.configuration.AuthProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Cluster-wide, atomic sliding-window-counter rate limiter. Every caller passes its own budget (max
 * + window), so a single Redis Lua script serves register, login (by-IP and by-user), and refresh
 * throttling without duplicating adapters.
 */
@Component
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-THROTTLE-REDIS")
public class RedisAuthThrottleAdapter implements AuthThrottlePort {

  private final StringRedisTemplate stringRedisTemplate;
  private final RedisScript<Long> slidingWindowRateLimiterScript;
  private final AuthProperties authProperties;

  @Override
  public boolean tryConsume(String key, int maxPerWindow, Duration window) {
    if (window == null || window.isZero() || window.isNegative()) {
      throw new IllegalArgumentException("window must be positive");
    }
    String redisKey = authProperties.redis().throttle().keyPrefix() + key;
    long windowSeconds = window.getSeconds();

    Long allowed =
        stringRedisTemplate.execute(
            slidingWindowRateLimiterScript,
            List.of(redisKey),
            String.valueOf(maxPerWindow),
            String.valueOf(windowSeconds));

    boolean result = allowed != null && allowed == 1L;
    log.debug(
        "[tryConsume] evaluated rate limit key={} maxPerWindow={} windowSec={} allowed={}",
        key,
        maxPerWindow,
        windowSeconds,
        result);
    return result;
  }
}
