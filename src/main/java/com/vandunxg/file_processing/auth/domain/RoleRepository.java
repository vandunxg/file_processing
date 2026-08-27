package com.vandunxg.file_processing.auth.domain;

import java.time.Instant;
import java.util.*;

import com.vandunxg.file_processing.auth.domain.model.Role;
import com.vandunxg.file_processing.auth.domain.model.RolePermission;

public interface RoleRepository {

  Optional<Role> findByCode(String code);

  Optional<Role> findById(UUID id);

  List<Role> findByIds(Set<UUID> ids);

  List<Role> findAll();

  Role save(Role role);

  Role lockAdminRole();

  void replacePermissions(UUID roleId, Collection<RolePermission> permissions, Instant now);

  List<UUID> findActiveUserIdsByRoleIds(Set<UUID> roleIds);
}
