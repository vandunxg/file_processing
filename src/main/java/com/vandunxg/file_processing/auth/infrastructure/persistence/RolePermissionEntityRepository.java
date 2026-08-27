package com.vandunxg.file_processing.auth.infrastructure.persistence;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.vandunxg.file_processing.auth.infrastructure.persistence.entity.RolePermissionEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RolePermissionEntityRepository extends JpaRepository<RolePermissionEntity, UUID> {

  List<RolePermissionEntity> findByRoleIdInAndDeletedAtIsNull(Collection<UUID> roleIds);

  List<RolePermissionEntity> findByRoleIdAndDeletedAtIsNull(UUID roleId);
}
