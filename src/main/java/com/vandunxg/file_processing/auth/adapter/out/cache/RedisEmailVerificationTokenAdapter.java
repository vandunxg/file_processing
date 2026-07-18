package com.vandunxg.file_processing.auth.adapter.out.cache;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.vandunxg.common.utils.MapperFactoryUtils;
import com.vandunxg.file_processing.auth.application.port.out.EmailVerificationTokenRepositoryPort;
import com.vandunxg.file_processing.auth.configuration.AuthProperties;
import com.vandunxg.file_processing.auth.domain.model.EmailVerificationToken;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/**
 * Redis-only token store. Replaces {@code EmailVerificationTokenPersistenceAdapter} — no row is
 * ever written to {@code auth_email_verification_tokens} anymore.
 */
@Repository
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-EMAIL-VERIFICATION-TOKEN-REDIS")
public class RedisEmailVerificationTokenAdapter implements EmailVerificationTokenRepositoryPort {

  private static final ObjectMapper OBJECT_MAPPER = MapperFactoryUtils.jacksonMapper();

  private final StringRedisTemplate stringRedisTemplate;
  private final RedisScript<Long> emailVerificationIssueScript;
  private final RedisScript<Long> emailVerificationInvalidateScript;
  private final AuthProperties authProperties;

  @Override
  public EmailVerificationToken save(EmailVerificationToken token) {
    if (token.getUsedAt() != null) {
      // Already atomically removed from Redis by findByTokenHashForUpdate's GETDEL — there is
      // nothing left to persist. This branch only exists because VerifyEmailService's
      // find-then-consume-then-save call pattern is shared with the JPA adapter this replaces,
      // which did need a second write to record usedAt.
      return token;
    }

    String payload =
        OBJECT_MAPPER.writeValueAsString(
            new EmailVerificationTokenRedisPayload(
                token.getId(),
                token.getUserId(),
                token.getIssuedAt(),
                token.getExpiresAt(),
                token.getIpAddressHash()));
    long ttlSeconds =
        Math.max(1, Duration.between(Instant.now(), token.getExpiresAt()).getSeconds());

    log.debug("[save] issuing email verification token userId={}", token.getUserId());
    stringRedisTemplate.execute(
        emailVerificationIssueScript,
        List.of(tokenKey(token.getTokenHash()), userKey(token.getUserId())),
        payload,
        token.getTokenHash(),
        String.valueOf(ttlSeconds));
    return token;
  }

  @Override
  public Optional<EmailVerificationToken> findByTokenHashForUpdate(String tokenHash) {
    String json = stringRedisTemplate.opsForValue().getAndDelete(tokenKey(tokenHash));
    if (json == null) {
      return Optional.empty();
    }

    EmailVerificationTokenRedisPayload payload =
        OBJECT_MAPPER.readValue(json, EmailVerificationTokenRedisPayload.class);
    return Optional.of(
        EmailVerificationToken.builder()
            .id(payload.id())
            .userId(payload.userId())
            .tokenHash(tokenHash)
            .issuedAt(payload.issuedAt())
            .expiresAt(payload.expiresAt())
            .ipAddressHash(payload.ipAddressHash())
            .build());
  }

  @Override
  public void invalidateAllForUser(UUID userId, Instant now) {
    stringRedisTemplate.execute(
        emailVerificationInvalidateScript,
        List.of(userKey(userId)),
        authProperties.redis().emailVerification().tokenKeyPrefix());
    log.debug("[invalidateAllForUser] invalidated prior token userId={}", userId);
  }

  private String tokenKey(String tokenHash) {
    return authProperties.redis().emailVerification().tokenKeyPrefix() + tokenHash;
  }

  private String userKey(UUID userId) {
    return authProperties.redis().emailVerification().userKeyPrefix() + userId;
  }
}
