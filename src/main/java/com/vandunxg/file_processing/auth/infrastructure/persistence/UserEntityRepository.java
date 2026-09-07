package com.vandunxg.file_processing.auth.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.vandunxg.file_processing.auth.infrastructure.persistence.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface UserEntityRepository
    extends JpaRepository<UserEntity, UUID>, UserEntityRepositoryCustom {

  boolean existsByNormalizedUsernameAndDeletedAtIsNull(String normalizedUsername);

  boolean existsByNormalizedEmailAndDeletedAtIsNull(String normalizedEmail);

  Optional<UserEntity> findByIdAndDeletedAtIsNull(UUID id);

  Optional<UserEntity> findByNormalizedUsernameAndDeletedAtIsNull(String normalizedUsername);

  Optional<UserEntity> findByNormalizedEmailAndDeletedAtIsNull(String normalizedEmail);

  @Query("select u.credentialVersion from UserEntity u where u.id = :id and u.deletedAt is null")
  Optional<Integer> findCredentialVersionByIdAndDeletedAtIsNull(UUID id);

  List<UserEntity> findAllByDeletedAtIsNullOrderByCreatedAtDesc();

  @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  Optional<UserEntity> findWithLockByIdAndDeletedAtIsNull(UUID id);

  /**
   * Bumps the credential version of a batch of users in one statement. Used when a role change
   * invalidates every holder's tokens: loading and locking each user instead would hold one row
   * lock per member of the role for the whole transaction.
   */
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      """
      UPDATE UserEntity u
      SET u.credentialVersion = u.credentialVersion + 1
      WHERE u.id IN :userIds AND u.deletedAt IS NULL
      """)
  int bumpCredentialVersionFor(@Param("userIds") Collection<UUID> userIds);

  @Query(
      "select count(u) from UserEntity u join UserRoleEntity ur on ur.userId = u.id "
          + "join RoleEntity r on r.id = ur.roleId "
          + "where r.code = 'ADMIN' and r.deletedAt is null and ur.deletedAt is null "
          + "and u.deletedAt is null and u.status = com.vandunxg.file_processing.auth.domain.model.UserStatus.ACTIVE")
  long countActiveAdmins();
}
