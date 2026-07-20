package com.vandunxg.file_processing.auth.adapter.out.persistence.entity;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaSessionRepository extends JpaRepository<SessionEntity, UUID> {

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
}
