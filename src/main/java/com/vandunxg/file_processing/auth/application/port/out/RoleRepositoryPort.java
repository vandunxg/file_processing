package com.vandunxg.file_processing.auth.application.port.out;

import java.time.Instant;
import java.util.*;

import com.vandunxg.file_processing.auth.application.query.RoleSearchQuery;
import com.vandunxg.file_processing.auth.domain.model.Role;
import com.vandunxg.file_processing.auth.domain.model.RolePermission;

public interface RoleRepositoryPort {

  Optional<Role> findByCode(String code);

  Optional<Role> findById(UUID id);

  List<Role> findByIds(Set<UUID> ids);

  List<Role> findAll();

  Role save(Role role);

  Role lockAdminRole();

  void replacePermissions(UUID roleId, Collection<RolePermission> permissions, Instant now);

  List<UUID> findActiveUserIdsByRoleIds(Set<UUID> roleIds);

  Long count(RoleSearchQuery query);

  List<Role> search(RoleSearchQuery query);
}
