package com.vandunxg.file_processing.auth.adapter.out.persistence.entity;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface JpaUserRepository extends JpaRepository<UserEntity, UUID> {

  boolean existsByNormalizedUsernameAndDeletedAtIsNull(String normalizedUsername);

  boolean existsByNormalizedEmailAndDeletedAtIsNull(String normalizedEmail);

  Optional<UserEntity> findByIdAndDeletedAtIsNull(UUID id);

  Optional<UserEntity> findByNormalizedUsernameAndDeletedAtIsNull(String normalizedUsername);

  Optional<UserEntity> findByNormalizedEmailAndDeletedAtIsNull(String normalizedEmail);

  @Query("select u.credentialVersion from UserEntity u where u.id = :id and u.deletedAt is null")
  Optional<Integer> findCredentialVersionByIdAndDeletedAtIsNull(UUID id);
}
