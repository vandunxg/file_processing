package com.vandunxg.file_processing.auth.infrastructure.cache;

import java.util.Optional;
import java.util.UUID;

import com.vandunxg.file_processing.auth.application.AuthProperties;
import com.vandunxg.file_processing.auth.application.capability.CredentialVersionCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-CV-CACHE")
public class RedisCredentialVersionCache implements CredentialVersionCache {

  private final StringRedisTemplate redisTemplate;
  private final AuthProperties authProperties;

  @Override
  public Optional<Integer> get(UUID userId) {
    String raw = redisTemplate.opsForValue().get(key(userId));
    if (raw == null) {
      return Optional.empty();
    }
    try {
      return Optional.of(Integer.parseInt(raw));
    } catch (NumberFormatException e) {
      log.warn("[get] malformed cv cache value userId={} value={}", userId, raw);
      redisTemplate.delete(key(userId));
      return Optional.empty();
    }
  }

  @Override
  public void put(UUID userId, int credentialVersion) {
    redisTemplate
        .opsForValue()
        .set(
            key(userId),
            String.valueOf(credentialVersion),
            authProperties.session().credentialVersionCacheTtl());
  }

  @Override
  public void invalidate(UUID userId) {
    redisTemplate.delete(key(userId));
  }

  private String key(UUID userId) {
    return authProperties.redis().credentialVersion().keyPrefix() + userId;
  }
}
