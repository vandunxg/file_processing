package com.vandunxg.file_processing.auth.adapter.out.persistence.entity;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.custom.JpaRoleRepositoryCustom;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface JpaRoleRepository
    extends JpaRepository<RoleEntity, UUID>, JpaRoleRepositoryCustom {

  Optional<RoleEntity> findByCodeAndDeletedAtIsNull(String code);

  Optional<RoleEntity> findByIdAndDeletedAtIsNull(UUID id);

  List<RoleEntity> findByIdInAndDeletedAtIsNull(Set<UUID> ids);

  List<RoleEntity> findAllByDeletedAtIsNull();

  @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
  Optional<RoleEntity> findWithLockByCodeAndDeletedAtIsNull(String code);

  // Batch-shaped so a single user and a list of users share the same query path (:userIds
  // works for a singleton Set too) — avoids N+1 if a multi-user read is added later.
  @Query(
      """
         select new com.vandunxg.file_processing.auth.adapter.out.persistence.entity.UserRoleAssociation(ur.userId, r)
         from RoleEntity r
         join UserRoleEntity ur on ur.roleId = r.id
         where ur.userId in :userIds and ur.deletedAt is null and r.deletedAt is null
      """)
  List<UserRoleAssociation> findRolesByUserIds(Set<UUID> userIds);

  @Query(
      "select distinct ur.userId from UserRoleEntity ur where ur.roleId in :roleIds and ur.deletedAt is null")
  List<UUID> findActiveUserIdsByRoleIds(Set<UUID> roleIds);
}
