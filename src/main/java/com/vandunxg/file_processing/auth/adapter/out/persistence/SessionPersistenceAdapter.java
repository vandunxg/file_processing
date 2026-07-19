package com.vandunxg.file_processing.auth.adapter.out.persistence;

import java.time.Instant;
import java.util.UUID;

import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.JpaSessionRepository;
import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.SessionEntity;
import com.vandunxg.file_processing.auth.adapter.out.persistence.mapper.SessionPersistenceMapper;
import com.vandunxg.file_processing.auth.application.port.out.SessionArchivePort;
import com.vandunxg.file_processing.auth.domain.model.RevocationReason;
import com.vandunxg.file_processing.auth.domain.model.Session;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-SESSION-PERSISTENCE")
public class SessionPersistenceAdapter implements SessionArchivePort {

  private final JpaSessionRepository jpaSessionRepository;
  private final SessionPersistenceMapper sessionPersistenceMapper;

  @Override
  @Transactional
  public void save(Session session) {
    log.debug("[save] archiving session sid={}", session.getId());
    jpaSessionRepository.save(sessionPersistenceMapper.toEntity(session));
  }

  @Override
  @Transactional
  public void recordRotation(UUID sessionId, String newRefreshTokenHash, Instant lastUsedAt) {
    jpaSessionRepository
        .findByIdAndDeletedAtIsNull(sessionId)
        .ifPresent(
            entity -> {
              entity.setRefreshTokenHash(newRefreshTokenHash);
              entity.setLastUsedAt(lastUsedAt);
              jpaSessionRepository.save(entity);
              log.debug("[recordRotation] archived rotation sid={}", sessionId);
            });
  }

  @Override
  @Transactional
  public void recordRevocation(UUID sessionId, RevocationReason reason, Instant revokedAt) {
    jpaSessionRepository
        .findByIdAndDeletedAtIsNull(sessionId)
        .ifPresent(
            entity -> {
              if (entity.getRevokedAt() == null) {
                entity.setRevokedAt(revokedAt);
                entity.setRevokedReason(reason);
                jpaSessionRepository.save(entity);
              }
              log.debug(
                  "[recordRevocation] archived revocation sid={} reason={}", sessionId, reason);
            });
  }

  @Override
  @Transactional
  public void recordRevocationForUser(UUID userId, RevocationReason reason, Instant revokedAt) {
    int rows = jpaSessionRepository.revokeAllForUser(userId, revokedAt, reason);
    log.debug(
        "[recordRevocationForUser] archived bulk revocation userId={} rows={} reason={}",
        userId,
        rows,
        reason);
  }

  // package-private for the read fallback used by tests when Redis is unavailable
  SessionEntity findByIdRaw(UUID id) {
    return jpaSessionRepository.findByIdAndDeletedAtIsNull(id).orElse(null);
  }
}
