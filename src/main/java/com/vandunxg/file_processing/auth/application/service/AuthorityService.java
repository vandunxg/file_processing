package com.vandunxg.file_processing.auth.application.service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.vandunxg.file_processing.auth.application.port.out.RoleRepositoryPort;
import com.vandunxg.file_processing.auth.domain.model.Role;
import com.vandunxg.file_processing.auth.domain.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthorityService {

  private final RoleRepositoryPort roleRepositoryPort;

  public List<String> permissionsFor(User user) {
    if (user == null || user.getRoles() == null || user.getRoles().isEmpty()) {
      return List.of();
    }
    Map<UUID, Role> roles =
        roleRepositoryPort.findAll().stream()
            .collect(Collectors.toMap(Role::getId, Function.identity()));
    Set<String> authorities = new LinkedHashSet<>();
    user.getRoles().stream()
        .map(Role::getId)
        .map(roles::get)
        .forEach(role -> collectPermissions(role, roles, authorities, new LinkedHashSet<>()));
    return List.copyOf(authorities);
  }

  private static void collectPermissions(
      Role role, Map<UUID, Role> roles, Collection<String> authorities, Set<UUID> visited) {
    if (role == null || !role.isActive() || !visited.add(role.getId())) {
      return;
    }
    role.getPermissions().stream()
        .map(permission -> permission.authority())
        .forEach(authorities::add);
    collectPermissions(roles.get(role.getRoleInheritedId()), roles, authorities, visited);
  }
}
