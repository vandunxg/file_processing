package com.vandunxg.file_processing.auth.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.vandunxg.file_processing.auth.infrastructure.persistence.entity.SessionEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SessionEntityRepository extends JpaRepository<SessionEntity, UUID> {

  Optional<SessionEntity> findByIdAndDeletedAtIsNull(UUID id);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT s FROM SessionEntity s WHERE s.id = :id")
  Optional<SessionEntity> findByIdForUpdate(@Param("id") UUID id);

  List<SessionEntity> findAllByUserIdAndDeletedAtIsNullAndRevokedAtIsNullOrderByLastUsedAtDesc(
      UUID userId);

  @Modifying
  @Query(
      """
      UPDATE SessionEntity s
      SET s.revokedAt = :now, s.revokedReason = :reason
      WHERE s.userId = :userId
        AND s.revokedAt IS NULL
        AND s.deletedAt IS NULL
      """)
  int revokeAllForUser(
      @Param("userId") UUID userId,
      @Param("now") Instant now,
      @Param("reason") com.vandunxg.file_processing.auth.domain.model.RevocationReason reason);

  /** Batch form of {@link #revokeAllForUser}, for role changes that hit many users at once. */
  @Modifying
  @Query(
      """
      UPDATE SessionEntity s
      SET s.revokedAt = :now, s.revokedReason = :reason
      WHERE s.userId IN :userIds
        AND s.revokedAt IS NULL
        AND s.deletedAt IS NULL
      """)
  int revokeAllForUsers(
      @Param("userIds") java.util.Collection<UUID> userIds,
      @Param("now") Instant now,
      @Param("reason") com.vandunxg.file_processing.auth.domain.model.RevocationReason reason);

  @Query(
      value =
          """
          SELECT id FROM auth_refresh_sessions
          WHERE deleted_at IS NULL
            AND (expires_at <= :now OR revoked_at IS NOT NULL)
          ORDER BY expires_at, revoked_at, id
          LIMIT :limit
          """,
      nativeQuery = true)
  List<UUID> findExpiredOrRevokedIds(@Param("now") Instant now, @Param("limit") int limit);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("DELETE FROM SessionEntity s WHERE s.id IN :sessionIds")
  int deleteAllByIdIn(@Param("sessionIds") List<UUID> sessionIds);
}
