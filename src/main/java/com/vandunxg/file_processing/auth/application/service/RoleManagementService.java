package com.vandunxg.file_processing.auth.application.service;

import com.vandunxg.common.models.dto.PageDTO;
import com.vandunxg.common.models.enums.Action;
import com.vandunxg.common.utils.IdUtils;
import com.vandunxg.file_processing.auth.application.port.in.SearchRolesUseCase;
import com.vandunxg.file_processing.auth.application.port.out.*;
import com.vandunxg.file_processing.auth.application.query.RoleSearchQuery;
import com.vandunxg.file_processing.auth.domain.exception.AuthDomainException;
import com.vandunxg.file_processing.auth.domain.exception.AuthErrorCode;
import com.vandunxg.file_processing.auth.domain.model.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RoleManagementService implements SearchRolesUseCase {

  private final RoleRepositoryPort roleRepositoryPort;
  private final UserRepositoryPort userRepositoryPort;
  private final SessionRepositoryPort sessionRepositoryPort;
  private final CredentialVersionCachePort credentialVersionCachePort;
  private final AuditLogEventPublisherPort auditLogEventPublisherPort;
  private final Clock clock;

  @Override
  @Transactional(readOnly = true)
  public PageDTO<Role> search(RoleSearchQuery query) {
    long count = roleRepositoryPort.count(query);

    if (count == 0) {
      return PageDTO.of(List.of(), query.getPageIndex(), query.getPageSize(), 0);
    }

    return PageDTO.of(
      roleRepositoryPort.search(query), query.getPageIndex(), query.getPageSize(), count);
  }

  @Transactional(readOnly = true)
  public Role detail(UUID roleId) {
    return roleRepositoryPort
      .findById(roleId)
      .orElseThrow(() -> new AuthDomainException(AuthErrorCode.ROLE_NOT_FOUND));
  }

  @Transactional
  public Role create(
    UUID actorId, String code, String name, String description, Set<PermissionSpec> permissions) {
    String normalizedCode = code == null ? null : code.trim().toUpperCase(java.util.Locale.ROOT);
    if (normalizedCode == null || normalizedCode.isBlank()) {
      throw new IllegalArgumentException("Role code is required");
    }
    if (roleRepositoryPort.findByCode(normalizedCode).isPresent()) {
      throw new AuthDomainException(AuthErrorCode.ROLE_CODE_ALREADY_EXISTS);
    }
    Instant now = Instant.now(clock);
    Role saved = roleRepositoryPort.save(Role.create(normalizedCode, name, description, now));
    roleRepositoryPort.replacePermissions(
      saved.getId(), permissionsFor(saved.getId(), permissions), now);
    publishAfterCommit(audit(actorId, saved.getId(), OperationType.CREATE, now));
    return detail(saved.getId());
  }

  @Transactional
  public Role update(
    UUID actorId,
    UUID roleId,
    String code,
    String name,
    String description,
    Set<PermissionSpec> permissions) {
    Role role = detail(roleId);
    String normalizedCode = code == null ? null : code.trim().toUpperCase(java.util.Locale.ROOT);
    roleRepositoryPort
      .findByCode(normalizedCode)
      .filter(existing -> !existing.getId().equals(roleId))
      .ifPresent(
        existing -> {
          throw new AuthDomainException(AuthErrorCode.ROLE_CODE_ALREADY_EXISTS);
        });
    try {
      role.update(normalizedCode, name, description);
    } catch (IllegalStateException exception) {
      throw new AuthDomainException(AuthErrorCode.ROLE_IS_CONST);
    }
    Set<RolePermission> newPermissions = permissionsFor(roleId, permissions);
    if ("ADMIN".equals(role.getCode()) && !isAdminPermissionSet(newPermissions)) {
      throw new AuthDomainException(AuthErrorCode.LAST_ACTIVE_ADMIN);
    }
    Instant now = Instant.now(clock);
    roleRepositoryPort.save(role);
    roleRepositoryPort.replacePermissions(roleId, newPermissions, now);
    invalidateUsersFor(roleAndDescendants(roleId));
    publishAfterCommit(audit(actorId, roleId, OperationType.ROLE_PERMISSION_UPDATED, now));
    return detail(roleId);
  }

  @Transactional
  public Role setInheritance(UUID actorId, UUID roleId, UUID parentRoleId) {
    Role child = detail(roleId);
    if (child.isConst()) {
      throw new AuthDomainException(AuthErrorCode.ROLE_IS_CONST);
    }
    Map<UUID, Role> roles = rolesById();
    if (parentRoleId != null && !roles.containsKey(parentRoleId)) {
      throw new AuthDomainException(AuthErrorCode.ROLE_NOT_FOUND);
    }
    for (UUID cursor = parentRoleId; cursor != null; ) {
      if (roleId.equals(cursor)) {
        throw new AuthDomainException(AuthErrorCode.ROLE_INHERITANCE_CYCLE);
      }
      Role current = roles.get(cursor);
      cursor = current == null ? null : current.getRoleInheritedId();
    }
    child.setInheritedRole(parentRoleId);
    Instant now = Instant.now(clock);
    roleRepositoryPort.save(child);
    invalidateUsersFor(roleAndDescendants(roleId));
    publishAfterCommit(audit(actorId, roleId, OperationType.ROLE_INHERITANCE_UPDATED, now));
    return detail(roleId);
  }

  @Transactional
  public Role activate(UUID actorId, UUID roleId) {
    Role role = detail(roleId);
    if (role.isActive()) {
      return role;
    }
    role.activate();
    Instant now = Instant.now(clock);
    Role saved = roleRepositoryPort.save(role);
    invalidateUsersFor(roleAndDescendants(roleId));
    publishAfterCommit(audit(actorId, roleId, OperationType.ACTIVATED, now));
    return saved;
  }

  @Transactional
  public Role inactivate(UUID actorId, UUID roleId) {
    Role role = detail(roleId);
    if ("ADMIN".equals(role.getCode())) {
      throw new AuthDomainException(AuthErrorCode.LAST_ACTIVE_ADMIN);
    }
    if (!role.isActive()) {
      return role;
    }
    role.inactivate();
    Instant now = Instant.now(clock);
    Role saved = roleRepositoryPort.save(role);
    invalidateUsersFor(roleAndDescendants(roleId));
    publishAfterCommit(audit(actorId, roleId, OperationType.DEACTIVATED, now));
    return saved;
  }

  @Transactional
  public void delete(UUID actorId, UUID roleId) {
    Role role = detail(roleId);
    if (role.isConst()) {
      throw new AuthDomainException(AuthErrorCode.ROLE_IS_CONST);
    }
    if (role.isActive()) {
      throw new AuthDomainException(AuthErrorCode.ROLE_IS_ACTIVE);
    }
    if (!roleRepositoryPort.findActiveUserIdsByRoleIds(Set.of(roleId)).isEmpty()) {
      throw new AuthDomainException(AuthErrorCode.ROLE_STILL_ASSIGNED);
    }
    Instant now = Instant.now(clock);
    role.delete(now);
    roleRepositoryPort.save(role);
    publishAfterCommit(audit(actorId, roleId, OperationType.DELETE, now));
  }

  private Set<RolePermission> permissionsFor(UUID roleId, Set<PermissionSpec> permissions) {
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
    return roleRepositoryPort.findAll().stream()
      .collect(Collectors.toMap(Role::getId, role -> role));
  }

  private void invalidateUsersFor(Set<UUID> roleIds) {
    Instant now = Instant.now(clock);
    roleRepositoryPort.findActiveUserIdsByRoleIds(roleIds).stream()
      .distinct()
      .forEach(
        userId ->
          userRepositoryPort
            .findByIdForUpdate(userId)
            .ifPresent(
              user -> {
                user.invalidateCredentials();
                userRepositoryPort.save(user);
                sessionRepositoryPort.revokeAllForUser(
                  userId, RevocationReason.ADMIN, now);
                afterCommit(() -> credentialVersionCachePort.invalidate(userId));
              }));
  }

  private AuditLog audit(UUID actorId, UUID roleId, OperationType operation, Instant now) {
    return AuditLog.builder()
      .id(IdUtils.nextId())
      .domain(AuditLogDomain.ROLE)
      .objectId(roleId)
      .operation(operation)
      .changedBy(actorId)
      .changedAt(now)
      .build();
  }

  private void publishAfterCommit(AuditLog audit) {
    afterCommit(() -> auditLogEventPublisherPort.publish(audit));
  }

  private static void afterCommit(Runnable action) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
      new TransactionSynchronization() {
        @Override
        public void afterCommit() {
          action.run();
        }
      });
  }

  public record PermissionSpec(ResourceCode resourceCode, Set<Action> actions) {
  }
}
