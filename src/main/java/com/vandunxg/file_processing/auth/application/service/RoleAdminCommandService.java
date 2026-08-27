package com.vandunxg.file_processing.auth.application.service;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.vandunxg.common.models.enums.Action;
import com.vandunxg.common.utils.IdUtils;
import com.vandunxg.file_processing.auth.application.AfterCommit;
import com.vandunxg.file_processing.auth.application.AuditTrail;
import com.vandunxg.file_processing.auth.application.capability.CredentialVersionCache;
import com.vandunxg.file_processing.auth.application.command.CreateRoleCommand;
import com.vandunxg.file_processing.auth.application.command.RoleActionCommand;
import com.vandunxg.file_processing.auth.application.command.RolePermissionCommand;
import com.vandunxg.file_processing.auth.application.command.SetRoleInheritanceCommand;
import com.vandunxg.file_processing.auth.application.command.UpdateRoleCommand;
import com.vandunxg.file_processing.auth.application.exception.AuthErrorCode;
import com.vandunxg.file_processing.auth.application.exception.AuthException;
import com.vandunxg.file_processing.auth.domain.RoleRepository;
import com.vandunxg.file_processing.auth.domain.SessionRepository;
import com.vandunxg.file_processing.auth.domain.UserRepository;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import com.vandunxg.file_processing.auth.domain.model.AuditLogDomain;
import com.vandunxg.file_processing.auth.domain.model.OperationType;
import com.vandunxg.file_processing.auth.domain.model.ResourceCode;
import com.vandunxg.file_processing.auth.domain.model.RevocationReason;
import com.vandunxg.file_processing.auth.domain.model.Role;
import com.vandunxg.file_processing.auth.domain.model.RolePermission;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Administrative writes over roles. A permission or inheritance change invalidates the credentials
 * of every user holding the role or one of its descendants, so outstanding access tokens cannot
 * keep the old authority set.
 */
@Service
@RequiredArgsConstructor
public class RoleAdminCommandService {

  private final RoleRepository roleRepository;
  private final UserRepository userRepository;
  private final SessionRepository sessionRepository;
  private final CredentialVersionCache credentialVersionCache;
  private final AuditTrail auditTrail;
  private final Clock clock;

  @Transactional
  public Role create(CreateRoleCommand command) {
    String normalizedCode = normalizeCode(command.code());
    if (normalizedCode == null || normalizedCode.isBlank()) {
      throw new IllegalArgumentException("Role code is required");
    }
    if (roleRepository.findByCode(normalizedCode).isPresent()) {
      throw new AuthException(AuthErrorCode.ROLE_CODE_ALREADY_EXISTS);
    }
    Instant now = Instant.now(clock);
    Role saved =
        roleRepository.save(
            Role.create(normalizedCode, command.name(), command.description(), now));
    roleRepository.replacePermissions(
        saved.getId(), permissionsFor(saved.getId(), command.permissions()), now);
    auditTrail.recordAfterCommit(
        audit(command.actorId(), saved.getId(), OperationType.CREATE, now));
    return requireRole(saved.getId());
  }

  @Transactional
  public Role update(UpdateRoleCommand command) {
    Role role = requireRole(command.roleId());
    String normalizedCode = normalizeCode(command.code());
    roleRepository
        .findByCode(normalizedCode)
        .filter(existing -> !existing.getId().equals(command.roleId()))
        .ifPresent(
            existing -> {
              throw new AuthException(AuthErrorCode.ROLE_CODE_ALREADY_EXISTS);
            });
    try {
      role.update(normalizedCode, command.name(), command.description());
    } catch (IllegalStateException exception) {
      throw new AuthException(AuthErrorCode.ROLE_IS_CONST);
    }
    Set<RolePermission> newPermissions = permissionsFor(command.roleId(), command.permissions());
    if ("ADMIN".equals(role.getCode()) && !isAdminPermissionSet(newPermissions)) {
      throw new AuthException(AuthErrorCode.AUTH_LAST_ACTIVE_ADMIN);
    }
    Instant now = Instant.now(clock);
    roleRepository.save(role);
    roleRepository.replacePermissions(command.roleId(), newPermissions, now);
    invalidateUsersFor(roleAndDescendants(command.roleId()));
    auditTrail.recordAfterCommit(
        audit(command.actorId(), command.roleId(), OperationType.ROLE_PERMISSION_UPDATED, now));
    return requireRole(command.roleId());
  }

  @Transactional
  public Role setInheritance(SetRoleInheritanceCommand command) {
    Role child = requireRole(command.roleId());
    if (child.isConst()) {
      throw new AuthException(AuthErrorCode.ROLE_IS_CONST);
    }
    Map<UUID, Role> roles = rolesById();
    if (command.roleInheritedId() != null && !roles.containsKey(command.roleInheritedId())) {
      throw new AuthException(AuthErrorCode.ROLE_NOT_FOUND);
    }
    for (UUID cursor = command.roleInheritedId(); cursor != null; ) {
      if (command.roleId().equals(cursor)) {
        throw new AuthException(AuthErrorCode.ROLE_INHERITANCE_CYCLE);
      }
      Role current = roles.get(cursor);
      cursor = current == null ? null : current.getRoleInheritedId();
    }
    child.setInheritedRole(command.roleInheritedId());
    Instant now = Instant.now(clock);
    roleRepository.save(child);
    invalidateUsersFor(roleAndDescendants(command.roleId()));
    auditTrail.recordAfterCommit(
        audit(command.actorId(), command.roleId(), OperationType.ROLE_INHERITANCE_UPDATED, now));
    return requireRole(command.roleId());
  }

  @Transactional
  public Role activate(RoleActionCommand command) {
    Role role = requireRole(command.roleId());
    if (role.isActive()) {
      return role;
    }
    role.activate();
    Instant now = Instant.now(clock);
    Role saved = roleRepository.save(role);
    invalidateUsersFor(roleAndDescendants(command.roleId()));
    auditTrail.recordAfterCommit(
        audit(command.actorId(), command.roleId(), OperationType.ACTIVATED, now));
    return saved;
  }

  @Transactional
  public Role inactivate(RoleActionCommand command) {
    Role role = requireRole(command.roleId());
    if ("ADMIN".equals(role.getCode())) {
      throw new AuthException(AuthErrorCode.AUTH_LAST_ACTIVE_ADMIN);
    }
    if (!role.isActive()) {
      return role;
    }
    role.inactivate();
    Instant now = Instant.now(clock);
    Role saved = roleRepository.save(role);
    invalidateUsersFor(roleAndDescendants(command.roleId()));
    auditTrail.recordAfterCommit(
        audit(command.actorId(), command.roleId(), OperationType.DEACTIVATED, now));
    return saved;
  }

  @Transactional
  public void delete(RoleActionCommand command) {
    Role role = requireRole(command.roleId());
    if (role.isConst()) {
      throw new AuthException(AuthErrorCode.ROLE_IS_CONST);
    }
    if (role.isActive()) {
      throw new AuthException(AuthErrorCode.ROLE_IS_ACTIVE);
    }
    if (!roleRepository.findActiveUserIdsByRoleIds(Set.of(command.roleId())).isEmpty()) {
      throw new AuthException(AuthErrorCode.ROLE_STILL_ASSIGNED);
    }
    Instant now = Instant.now(clock);
    role.delete(now);
    roleRepository.save(role);
    auditTrail.recordAfterCommit(
        audit(command.actorId(), command.roleId(), OperationType.DELETE, now));
  }

  private Role requireRole(UUID roleId) {
    return roleRepository
        .findById(roleId)
        .orElseThrow(() -> new AuthException(AuthErrorCode.ROLE_NOT_FOUND));
  }

  private static String normalizeCode(String code) {
    return code == null ? null : code.trim().toUpperCase(Locale.ROOT);
  }

  private Set<RolePermission> permissionsFor(UUID roleId, Set<RolePermissionCommand> permissions) {
    if (permissions == null) {
      return Set.of();
    }
    return permissions.stream()
        .filter(spec -> spec.resourceCode() != null && spec.actions() != null)
        .flatMap(
            spec ->
                spec.actions().stream()
                    .map(action -> RolePermission.grant(roleId, spec.resourceCode(), action)))
        .collect(Collectors.toSet());
  }

  private static boolean isAdminPermissionSet(Set<RolePermission> permissions) {
    return permissions.stream()
        .anyMatch(
            permission ->
                permission.getResourceCode() == ResourceCode.ALL
                    && permission.getAction() == Action.MANAGE);
  }

  private Set<UUID> roleAndDescendants(UUID roleId) {
    Map<UUID, Role> roles = rolesById();
    Set<UUID> affected = new HashSet<>();
    affected.add(roleId);
    boolean changed;
    do {
      changed =
          roles.values().stream()
              .filter(role -> affected.contains(role.getRoleInheritedId()))
              .map(Role::getId)
              .anyMatch(affected::add);
    } while (changed);
    return affected;
  }

  private Map<UUID, Role> rolesById() {
    return roleRepository.findAll().stream().collect(Collectors.toMap(Role::getId, role -> role));
  }

  private void invalidateUsersFor(Set<UUID> roleIds) {
    Instant now = Instant.now(clock);
    roleRepository.findActiveUserIdsByRoleIds(roleIds).stream()
        .distinct()
        .forEach(
            userId ->
                userRepository
                    .findByIdForUpdate(userId)
                    .ifPresent(
                        user -> {
                          user.invalidateCredentials();
                          userRepository.save(user);
                          sessionRepository.revokeAllForUser(userId, RevocationReason.ADMIN, now);
                          AfterCommit.run(() -> credentialVersionCache.invalidate(userId));
                        }));
  }

  private static AuditLog audit(UUID actorId, UUID roleId, OperationType operation, Instant now) {
    return AuditLog.builder()
        .id(IdUtils.nextId())
        .domain(AuditLogDomain.ROLE)
        .objectId(roleId)
        .operation(operation)
        .changedBy(actorId)
        .changedAt(now)
        .build();
  }
}
