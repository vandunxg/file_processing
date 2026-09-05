package com.vandunxg.file_processing.auth.application.service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.vandunxg.file_processing.auth.domain.RoleRepository;
import com.vandunxg.file_processing.auth.domain.model.Role;
import com.vandunxg.file_processing.auth.domain.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthorityService {

  private final RoleRepository roleRepository;

  public List<String> permissionsFor(User user) {
    if (user == null || user.getRoles() == null || user.getRoles().isEmpty()) {
      return List.of();
    }
    Map<UUID, Role> roles =
        inheritanceClosure(user.getRoles().stream().map(Role::getId).collect(Collectors.toSet()));
    Set<String> authorities = new LinkedHashSet<>();
    user.getRoles().stream()
        .map(Role::getId)
        .map(roles::get)
        .forEach(role -> collectPermissions(role, roles, authorities, new LinkedHashSet<>()));
    return List.copyOf(authorities);
  }

  /**
   * Loads only the roles reachable from {@code seedIds} by following {@code roleInheritedId}, one
   * generation per round trip.
   *
   * <p>This runs on every authenticated request, so it deliberately does not load the whole role
   * table: inheritance chains are short, and fetching only what the user can reach keeps the cost
   * proportional to that user rather than to how many roles the system has defined.
   */
  private Map<UUID, Role> inheritanceClosure(Set<UUID> seedIds) {
    Map<UUID, Role> closure = new LinkedHashMap<>();
    Set<UUID> pending = new LinkedHashSet<>(seedIds);
    while (!pending.isEmpty()) {
      List<Role> generation = roleRepository.findByIds(pending);
      generation.forEach(role -> closure.put(role.getId(), role));
      pending =
          generation.stream()
              .map(Role::getRoleInheritedId)
              .filter(parentId -> parentId != null && !closure.containsKey(parentId))
              .collect(Collectors.toCollection(LinkedHashSet::new));
    }
    return closure;
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
