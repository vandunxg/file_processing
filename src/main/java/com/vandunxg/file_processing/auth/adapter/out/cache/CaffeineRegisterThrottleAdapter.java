package com.vandunxg.file_processing.auth.adapter.out.cache;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.vandunxg.file_processing.auth.application.port.out.RegisterThrottlePort;
import org.springframework.stereotype.Component;

/**
 * Fixed-window-per-first-request counter, per-instance (not cluster-wide). Acceptable for this
 * delivery's scope; not backed by Redis.
 */
@Component
public class CaffeineRegisterThrottleAdapter implements RegisterThrottlePort {

  private final Cache<String, AtomicInteger> counters =
      Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(1)).build();

  @Override
  public boolean tryConsume(String key, int maxPerHour) {
    int attempts =
        counters.asMap().computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
    return attempts <= maxPerHour;
  }
}
