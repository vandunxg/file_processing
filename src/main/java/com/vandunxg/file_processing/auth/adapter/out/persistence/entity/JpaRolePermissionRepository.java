package com.vandunxg.file_processing.auth.adapter.out.persistence.entity;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface JpaRolePermissionRepository extends JpaRepository<RolePermissionEntity, UUID> {

  List<RolePermissionEntity> findByRoleIdInAndDeletedAtIsNull(Collection<UUID> roleIds);

  List<RolePermissionEntity> findByRoleIdAndDeletedAtIsNull(UUID roleId);
}
