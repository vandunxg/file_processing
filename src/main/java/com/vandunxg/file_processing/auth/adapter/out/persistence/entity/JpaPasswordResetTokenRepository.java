package com.vandunxg.file_processing.auth.adapter.out.persistence.entity;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaPasswordResetTokenRepository
    extends JpaRepository<PasswordResetTokenEntity, UUID> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select t from PasswordResetTokenEntity t where t.tokenHash = :tokenHash")
  Optional<PasswordResetTokenEntity> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      "update PasswordResetTokenEntity t set t.usedAt = :now where t.userId = :userId and t.usedAt is null")
  void invalidateAllForUser(@Param("userId") UUID userId, @Param("now") Instant now);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          DELETE FROM auth_password_reset_tokens
          WHERE id IN (
            SELECT id FROM auth_password_reset_tokens
            WHERE expires_at <= :now
            ORDER BY expires_at, id
            LIMIT :limit
          )
          """,
      nativeQuery = true)
  int deleteExpired(@Param("now") Instant now, @Param("limit") int limit);
}
