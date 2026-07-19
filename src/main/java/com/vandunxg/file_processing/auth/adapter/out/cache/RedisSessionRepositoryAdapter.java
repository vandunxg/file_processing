package com.vandunxg.file_processing.auth.adapter.out.cache;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.vandunxg.file_processing.auth.application.port.out.SessionEventPublisherPort;
import com.vandunxg.file_processing.auth.application.port.out.SessionRepositoryPort;
import com.vandunxg.file_processing.auth.configuration.AuthProperties;
import com.vandunxg.file_processing.auth.domain.model.RevocationReason;
import com.vandunxg.file_processing.auth.domain.model.Session;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-SESSION-REDIS")
public class RedisSessionRepositoryAdapter implements SessionRepositoryPort {

  private static final String FIELD_USER_ID = "userId";
  private static final String FIELD_CREDENTIAL_VERSION = "credentialVersion";
  private static final String FIELD_REFRESH_HASH = "refreshTokenHash";
  private static final String FIELD_USER_AGENT = "userAgent";
  private static final String FIELD_IP_HASH = "ipAddressHash";
  private static final String FIELD_ISSUED_AT = "issuedAt";
  private static final String FIELD_LAST_USED_AT = "lastUsedAt";
  private static final String FIELD_EXPIRES_AT = "expiresAt";
  private static final String FIELD_REVOKED_AT = "revokedAt";
  private static final String FIELD_REVOKED_REASON = "revokedReason";

  private final StringRedisTemplate redisTemplate;
  private final RedisScript<Long> refreshTokenRotateScript;
  private final RedisScript<Long> sessionRevokeScript;
  private final SessionEventPublisherPort sessionEventPublisherPort;
  private final AuthProperties authProperties;

  @Override
  public void save(Session session) {
    String sessionKey = sessionKey(session.getId());
    Map<String, String> fields = new HashMap<>();
    fields.put(FIELD_USER_ID, session.getUserId().toString());
    fields.put(FIELD_CREDENTIAL_VERSION, String.valueOf(session.getCredentialVersion()));
    fields.put(FIELD_REFRESH_HASH, session.getRefreshTokenHash());
    if (session.getUserAgent() != null) {
      fields.put(FIELD_USER_AGENT, session.getUserAgent());
    }
    if (session.getIpAddressHash() != null) {
      fields.put(FIELD_IP_HASH, session.getIpAddressHash());
    }
    fields.put(FIELD_ISSUED_AT, session.getIssuedAt().toString());
    fields.put(FIELD_LAST_USED_AT, session.getLastUsedAt().toString());
    fields.put(FIELD_EXPIRES_AT, session.getExpiresAt().toString());

    Duration ttl = Duration.between(Instant.now(), session.getExpiresAt());
    if (ttl.isZero() || ttl.isNegative()) {
      throw new IllegalStateException("session already expired: " + session.getId());
    }
    redisTemplate.opsForHash().putAll(sessionKey, fields);
    redisTemplate.expire(sessionKey, ttl);

    String refreshKey = refreshKey(session.getRefreshTokenHash());
    redisTemplate.opsForValue().set(refreshKey, session.getId().toString(), ttl);

    redisTemplate.opsForSet().add(userSessionsKey(session.getUserId()), session.getId().toString());

    sessionEventPublisherPort.publishPersist(session);
    log.debug("[save] persisted session sid={} userId={}", session.getId(), session.getUserId());
  }

  @Override
  public Optional<Session> findActiveById(UUID sessionId, Instant now) {
    Map<Object, Object> fields = redisTemplate.opsForHash().entries(sessionKey(sessionId));
    if (fields == null || fields.isEmpty()) {
      return Optional.empty();
    }
    Session session = toDomain(sessionId, fields);
    if (session == null || !session.isActive(now)) {
      return Optional.empty();
    }
    return Optional.of(session);
  }

  @Override
  public Optional<UUID> resolveSessionIdByRefreshHash(String refreshHash) {
    String sid = redisTemplate.opsForValue().get(refreshKey(refreshHash));
    return sid == null ? Optional.empty() : Optional.of(UUID.fromString(sid));
  }

  @Override
  public Optional<UUID> resolveReusedSessionIdByHash(String refreshHash) {
    String sid = redisTemplate.opsForValue().get(refreshUsedKey(refreshHash));
    return sid == null ? Optional.empty() : Optional.of(UUID.fromString(sid));
  }

  @Override
  public boolean rotateRefresh(
      UUID sessionId,
      String oldRefreshHash,
      String newRefreshHash,
      Instant lastUsedAt,
      Instant expiresAt) {
    Duration remaining = Duration.between(Instant.now(), expiresAt);
    if (remaining.isZero() || remaining.isNegative()) {
      return false;
    }
    List<String> keys =
        List.of(
            refreshKey(oldRefreshHash),
            refreshUsedKey(oldRefreshHash),
            refreshKey(newRefreshHash),
            sessionKey(sessionId));
    Long result =
        redisTemplate.execute(
            refreshTokenRotateScript,
            keys,
            sessionId.toString(),
            newRefreshHash,
            String.valueOf(remaining.toMillis()),
            String.valueOf(authProperties.redis().refresh().reuseDetectionWindow().toMillis()),
            lastUsedAt.toString());
    boolean ok = result != null && result == 1L;
    if (ok) {
      sessionEventPublisherPort.publishRotation(sessionId, newRefreshHash, lastUsedAt);
    }
    return ok;
  }

  @Override
  public void revoke(UUID sessionId, RevocationReason reason, Instant now) {
    Map<Object, Object> fields = redisTemplate.opsForHash().entries(sessionKey(sessionId));
    if (fields == null || fields.isEmpty()) {
      return;
    }
    Session session = toDomain(sessionId, fields);
    String refreshKeyOrEmpty =
        session == null || session.getRefreshTokenHash() == null
            ? ""
            : refreshKey(session.getRefreshTokenHash());
    UUID userId = session == null ? null : session.getUserId();
    List<String> keys =
        List.of(
            sessionKey(sessionId),
            refreshKeyOrEmpty,
            userId == null ? "" : userSessionsKey(userId));
    if (userId != null) {
      redisTemplate.execute(sessionRevokeScript, keys, sessionId.toString());
    } else {
      redisTemplate.delete(sessionKey(sessionId));
    }
    sessionEventPublisherPort.publishRevocation(sessionId, reason, now);
    log.debug("[revoke] revoked session sid={} reason={}", sessionId, reason);
  }

  @Override
  public int revokeAllForUser(UUID userId, RevocationReason reason, Instant now) {
    Set<String> members = redisTemplate.opsForSet().members(userSessionsKey(userId));
    int count = 0;
    if (members != null) {
      for (String sidStr : members) {
        UUID sid = UUID.fromString(sidStr);
        Map<Object, Object> fields = redisTemplate.opsForHash().entries(sessionKey(sid));
        String refreshKeyOrEmpty =
            fields == null || !fields.containsKey(FIELD_REFRESH_HASH)
                ? ""
                : refreshKey(Objects.toString(fields.get(FIELD_REFRESH_HASH)));
        List<String> keys = List.of(sessionKey(sid), refreshKeyOrEmpty, userSessionsKey(userId));
        redisTemplate.execute(sessionRevokeScript, keys, sid.toString());
        count++;
      }
    }
    redisTemplate.delete(userSessionsKey(userId));
    sessionEventPublisherPort.publishRevocationForUser(userId, reason, now);
    log.info("[revokeAllForUser] revoked userId={} count={} reason={}", userId, count, reason);
    return count;
  }

  @Override
  public List<Session> listActiveByUser(UUID userId, Instant now) {
    Set<String> members = redisTemplate.opsForSet().members(userSessionsKey(userId));
    if (members == null || members.isEmpty()) {
      return List.of();
    }
    List<Session> out = new ArrayList<>();
    for (String sidStr : members) {
      UUID sid = UUID.fromString(sidStr);
      Map<Object, Object> fields = redisTemplate.opsForHash().entries(sessionKey(sid));
      if (fields == null || fields.isEmpty()) {
        redisTemplate.opsForSet().remove(userSessionsKey(userId), sidStr);
        continue;
      }
      Session s = toDomain(sid, fields);
      if (s != null && s.isActive(now)) {
        out.add(s);
      }
    }
    out.sort((a, b) -> b.getLastUsedAt().compareTo(a.getLastUsedAt()));
    return out;
  }

  private Session toDomain(UUID sessionId, Map<Object, Object> fields) {
    try {
      Session.SessionBuilder<?, ?> b =
          Session.builder()
              .id(sessionId)
              .userId(UUID.fromString(Objects.toString(fields.get(FIELD_USER_ID))))
              .credentialVersion(
                  Integer.parseInt(Objects.toString(fields.get(FIELD_CREDENTIAL_VERSION))))
              .refreshTokenHash(Objects.toString(fields.get(FIELD_REFRESH_HASH)))
              .issuedAt(Instant.parse(Objects.toString(fields.get(FIELD_ISSUED_AT))))
              .lastUsedAt(Instant.parse(Objects.toString(fields.get(FIELD_LAST_USED_AT))))
              .expiresAt(Instant.parse(Objects.toString(fields.get(FIELD_EXPIRES_AT))));
      if (fields.get(FIELD_USER_AGENT) != null) {
        b.userAgent(Objects.toString(fields.get(FIELD_USER_AGENT)));
      }
      if (fields.get(FIELD_IP_HASH) != null) {
        b.ipAddressHash(Objects.toString(fields.get(FIELD_IP_HASH)));
      }
      if (fields.get(FIELD_REVOKED_AT) != null) {
        b.revokedAt(Instant.parse(Objects.toString(fields.get(FIELD_REVOKED_AT))));
      }
      if (fields.get(FIELD_REVOKED_REASON) != null) {
        b.revokedReason(
            RevocationReason.valueOf(Objects.toString(fields.get(FIELD_REVOKED_REASON))));
      }
      return b.build();
    } catch (Exception e) {
      log.warn("[toDomain] malformed session hash sid={}", sessionId, e);
      return null;
    }
  }

  private String sessionKey(UUID sessionId) {
    return authProperties.redis().session().keyPrefix() + sessionId;
  }

  private String refreshKey(String refreshHash) {
    return authProperties.redis().refresh().keyPrefix() + refreshHash;
  }

  private String refreshUsedKey(String refreshHash) {
    return authProperties.redis().refresh().usedKeyPrefix() + refreshHash;
  }

  private String userSessionsKey(UUID userId) {
    return authProperties.redis().userSessions().keyPrefix() + userId;
  }
}
