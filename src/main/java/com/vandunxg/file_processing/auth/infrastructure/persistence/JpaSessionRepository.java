package com.vandunxg.file_processing.auth.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.vandunxg.file_processing.auth.domain.SessionRepository;
import com.vandunxg.file_processing.auth.domain.model.RevocationReason;
import com.vandunxg.file_processing.auth.domain.model.Session;
import com.vandunxg.file_processing.auth.infrastructure.persistence.entity.RefreshTokenEntity;
import com.vandunxg.file_processing.auth.infrastructure.persistence.entity.SessionEntity;
import com.vandunxg.file_processing.auth.infrastructure.persistence.mapper.SessionPersistenceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-SESSION-PERSISTENCE")
public class JpaSessionRepository implements SessionRepository {

  private final RefreshTokenEntityRepository refreshTokenEntityRepository;
  private final SessionEntityRepository sessionEntityRepository;
  private final SessionPersistenceMapper sessionPersistenceMapper;

  @Override
  @Transactional
  public void save(Session session, String initialRefreshTokenHash) {
    if (initialRefreshTokenHash == null || !initialRefreshTokenHash.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Invalid initial refresh token hash");
    }
    log.debug("[save] persisting refresh session sid={}", session.getId());
    sessionEntityRepository.save(sessionPersistenceMapper.toEntity(session));
    refreshTokenEntityRepository.save(
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
    return sessionEntityRepository
        .findByIdAndDeletedAtIsNull(sessionId)
        .map(sessionPersistenceMapper::toDomain);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<UUID> resolveSessionIdByRefreshHash(String refreshHash) {
    return refreshTokenEntityRepository
        .findByTokenHashAndConsumedAtIsNullAndRevokedAtIsNull(refreshHash)
        .map(RefreshTokenEntity::getSessionId);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<UUID> resolveReusedSessionIdByHash(String refreshHash) {
    return refreshTokenEntityRepository
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
        refreshTokenEntityRepository.findByTokenHashForUpdate(oldRefreshHash);
    if (token.isEmpty() || !token.get().getSessionId().equals(sessionId)) {
      return false;
    }
    Optional<SessionEntity> session = sessionEntityRepository.findByIdForUpdate(sessionId);
    if (session.isEmpty()
        || !sessionPersistenceMapper.toDomain(session.get()).isActive(lastUsedAt)
        || token.get().getConsumedAt() != null
        || token.get().getRevokedAt() != null) {
      return false;
    }

    token.get().setConsumedAt(lastUsedAt);
    session.get().setLastUsedAt(lastUsedAt);
    refreshTokenEntityRepository.save(
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
    sessionEntityRepository
        .findByIdForUpdate(sessionId)
        .ifPresent(
            session -> {
              if (session.getRevokedAt() == null) {
                session.setRevokedAt(now);
                session.setRevokedReason(reason);
              }
              refreshTokenEntityRepository.revokeAllForSession(sessionId, now);
            });
  }

  @Override
  @Transactional
  public int revokeAllForUser(UUID userId, RevocationReason reason, Instant now) {
    List<SessionEntity> sessions =
        sessionEntityRepository
            .findAllByUserIdAndDeletedAtIsNullAndRevokedAtIsNullOrderByLastUsedAtDesc(userId);
    sessions.forEach(session -> revoke(session.getId(), reason, now));
    return sessions.size();
  }

  @Override
  @Transactional(readOnly = true)
  public List<Session> listActiveByUser(UUID userId, Instant now) {
    return sessionEntityRepository
        .findAllByUserIdAndDeletedAtIsNullAndRevokedAtIsNullOrderByLastUsedAtDesc(userId)
        .stream()
        .map(sessionPersistenceMapper::toDomain)
        .filter(session -> session.isActive(now))
        .toList();
  }

  @Override
  @Transactional
  public int deleteExpiredOrRevoked(Instant now, int limit) {
    List<UUID> sessionIds = sessionEntityRepository.findExpiredOrRevokedIds(now, limit);
    if (sessionIds.isEmpty()) {
      return 0;
    }
    refreshTokenEntityRepository.deleteAllBySessionIdIn(sessionIds);
    return sessionEntityRepository.deleteAllByIdIn(sessionIds);
  }
}
