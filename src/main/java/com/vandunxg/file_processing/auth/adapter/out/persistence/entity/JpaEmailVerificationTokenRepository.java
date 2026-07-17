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
import org.springframework.stereotype.Repository;

@Repository
public interface JpaEmailVerificationTokenRepository
    extends JpaRepository<EmailVerificationTokenEntity, UUID> {

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT t FROM EmailVerificationTokenEntity t WHERE t.tokenHash = :hash")
  Optional<EmailVerificationTokenEntity> findByTokenHashForUpdate(@Param("hash") String hash);

  @Modifying
  @Query(
      "UPDATE EmailVerificationTokenEntity t SET t.usedAt = :now "
          + "WHERE t.userId = :userId AND t.usedAt IS NULL")
  int invalidateAllForUser(@Param("userId") UUID userId, @Param("now") Instant now);
}
