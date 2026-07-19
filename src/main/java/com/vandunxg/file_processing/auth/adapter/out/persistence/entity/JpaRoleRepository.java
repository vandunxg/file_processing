package com.vandunxg.file_processing.auth.adapter.out.persistence.entity;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

@Repository
public interface JpaRoleRepository extends JpaRepository<RoleEntity, UUID> {

  Optional<RoleEntity> findByCodeAndDeletedAtIsNull(String code);

  // Batch-shaped so a single user and a list of users share the same query path (:userIds
  // works for a singleton Set too) — avoids N+1 if a multi-user read is added later.
  @Query(
      """
      select new com.vandunxg.file_processing.auth.adapter.out.persistence.entity.UserRoleAssociation(ur.userId, r)
      from RoleEntity r
      join UserRoleEntity ur on ur.roleId = r.id
      where ur.userId in :userIds
    """)
  List<UserRoleAssociation> findRolesByUserIds(Set<UUID> userIds);
}
