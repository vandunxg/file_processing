package com.vandunxg.file_processing.auth.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.JpaRefreshTokenRepository;
import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.JpaSessionRepository;
import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.RefreshTokenEntity;
import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.SessionEntity;
import com.vandunxg.file_processing.auth.adapter.out.persistence.mapper.SessionPersistenceMapper;
import com.vandunxg.file_processing.auth.application.port.out.SessionRepositoryPort;
import com.vandunxg.file_processing.auth.domain.model.RevocationReason;
import com.vandunxg.file_processing.auth.domain.model.Session;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-SESSION-PERSISTENCE")
public class SessionPersistenceAdapter implements SessionRepositoryPort {

  private final JpaRefreshTokenRepository jpaRefreshTokenRepository;
  private final JpaSessionRepository jpaSessionRepository;
  private final SessionPersistenceMapper sessionPersistenceMapper;

  @Override
  @Transactional
  public void save(Session session, String initialRefreshTokenHash) {
    if (initialRefreshTokenHash == null || !initialRefreshTokenHash.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Invalid initial refresh token hash");
    }
    log.debug("[save] persisting refresh session sid={}", session.getId());
    jpaSessionRepository.save(sessionPersistenceMapper.toEntity(session));
    jpaRefreshTokenRepository.save(
        new RefreshTokenEntity(
            UUID.randomUUID(),
            session.getId(),
            null,
            initialRefreshTokenHash,
            session.getIssuedAt(),
            null,
            null));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Session> findActiveById(UUID sessionId, Instant now) {
    return findById(sessionId).filter(session -> session.isActive(now));
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Session> findById(UUID sessionId) {
    return jpaSessionRepository
        .findByIdAndDeletedAtIsNull(sessionId)
        .map(sessionPersistenceMapper::toDomain);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<UUID> resolveSessionIdByRefreshHash(String refreshHash) {
    return jpaRefreshTokenRepository
        .findByTokenHashAndConsumedAtIsNullAndRevokedAtIsNull(refreshHash)
        .map(RefreshTokenEntity::getSessionId);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<UUID> resolveReusedSessionIdByHash(String refreshHash) {
    return jpaRefreshTokenRepository
        .findReusedByTokenHash(refreshHash)
        .map(RefreshTokenEntity::getSessionId);
  }

  @Override
  @Transactional
  public boolean rotateRefresh(
      UUID sessionId,
      String oldRefreshHash,
      String newRefreshHash,
      Instant lastUsedAt,
      Instant expiresAt) {
    Optional<RefreshTokenEntity> token =
        jpaRefreshTokenRepository.findByTokenHashForUpdate(oldRefreshHash);
    if (token.isEmpty() || !token.get().getSessionId().equals(sessionId)) {
      return false;
    }
    Optional<SessionEntity> session = jpaSessionRepository.findByIdForUpdate(sessionId);
    if (session.isEmpty()
        || !sessionPersistenceMapper.toDomain(session.get()).isActive(lastUsedAt)
        || token.get().getConsumedAt() != null
        || token.get().getRevokedAt() != null) {
      return false;
    }

    token.get().setConsumedAt(lastUsedAt);
    session.get().setLastUsedAt(lastUsedAt);
    jpaRefreshTokenRepository.save(
        new RefreshTokenEntity(
            UUID.randomUUID(),
            sessionId,
            token.get().getId(),
            newRefreshHash,
            lastUsedAt,
            null,
            null));
    return true;
  }

  @Override
  @Transactional
  public void revoke(UUID sessionId, RevocationReason reason, Instant now) {
    jpaSessionRepository
        .findByIdForUpdate(sessionId)
        .ifPresent(
            session -> {
              if (session.getRevokedAt() == null) {
                session.setRevokedAt(now);
                session.setRevokedReason(reason);
              }
              jpaRefreshTokenRepository.revokeAllForSession(sessionId, now);
            });
  }

  @Override
  @Transactional
  public int revokeAllForUser(UUID userId, RevocationReason reason, Instant now) {
    List<SessionEntity> sessions =
        jpaSessionRepository
            .findAllByUserIdAndDeletedAtIsNullAndRevokedAtIsNullOrderByLastUsedAtDesc(userId);
    sessions.forEach(session -> revoke(session.getId(), reason, now));
    return sessions.size();
  }

  @Override
  @Transactional(readOnly = true)
  public List<Session> listActiveByUser(UUID userId, Instant now) {
    return jpaSessionRepository
        .findAllByUserIdAndDeletedAtIsNullAndRevokedAtIsNullOrderByLastUsedAtDesc(userId)
        .stream()
        .map(sessionPersistenceMapper::toDomain)
        .filter(session -> session.isActive(now))
        .toList();
  }

  @Override
  @Transactional
  public int deleteExpiredOrRevoked(Instant now, int limit) {
    List<UUID> sessionIds = jpaSessionRepository.findExpiredOrRevokedIds(now, limit);
    if (sessionIds.isEmpty()) {
      return 0;
    }
    jpaRefreshTokenRepository.deleteAllBySessionIdIn(sessionIds);
    return jpaSessionRepository.deleteAllByIdIn(sessionIds);
  }
}
