package com.vandunxg.file_processing.auth.infrastructure.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.vandunxg.file_processing.auth.infrastructure.persistence.entity.RefreshTokenEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefreshTokenEntityRepository extends JpaRepository<RefreshTokenEntity, UUID> {

  Optional<RefreshTokenEntity> findByTokenHashAndConsumedAtIsNullAndRevokedAtIsNull(
      String tokenHash);

  @Query(
      """
      SELECT t FROM RefreshTokenEntity t
      WHERE t.tokenHash = :tokenHash
        AND (t.consumedAt IS NOT NULL OR t.revokedAt IS NOT NULL)
      """)
  Optional<RefreshTokenEntity> findReusedByTokenHash(@Param("tokenHash") String tokenHash);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT t FROM RefreshTokenEntity t WHERE t.tokenHash = :tokenHash")
  Optional<RefreshTokenEntity> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

  @Modifying
  @Query(
      """
      UPDATE RefreshTokenEntity t
      SET t.revokedAt = :now
      WHERE t.sessionId = :sessionId AND t.revokedAt IS NULL
      """)
  int revokeAllForSession(@Param("sessionId") UUID sessionId, @Param("now") Instant now);

  /**
   * Revokes every live refresh token belonging to the given users in one statement, so a role
   * change does not have to walk their sessions one at a time.
   */
  @Modifying
  @Query(
      """
      UPDATE RefreshTokenEntity t
      SET t.revokedAt = :now
      WHERE t.revokedAt IS NULL
        AND t.sessionId IN (SELECT s.id FROM SessionEntity s WHERE s.userId IN :userIds)
      """)
  int revokeAllForUsers(
      @Param("userIds") java.util.Collection<UUID> userIds, @Param("now") Instant now);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("DELETE FROM RefreshTokenEntity t WHERE t.sessionId IN :sessionIds")
  int deleteAllBySessionIdIn(@Param("sessionIds") java.util.List<UUID> sessionIds);
}
