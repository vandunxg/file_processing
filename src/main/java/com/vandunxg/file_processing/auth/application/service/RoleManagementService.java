package com.vandunxg.file_processing.auth.application.service;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.vandunxg.common.models.dto.PageDTO;
import com.vandunxg.common.models.enums.Action;
import com.vandunxg.common.utils.IdUtils;
import com.vandunxg.file_processing.auth.application.command.CreateRoleCommand;
import com.vandunxg.file_processing.auth.application.command.RoleActionCommand;
import com.vandunxg.file_processing.auth.application.command.RolePermissionCommand;
import com.vandunxg.file_processing.auth.application.command.SetRoleInheritanceCommand;
import com.vandunxg.file_processing.auth.application.command.UpdateRoleCommand;
import com.vandunxg.file_processing.auth.application.port.in.RoleManagementUseCase;
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

@Service
@RequiredArgsConstructor
public class RoleManagementService implements SearchRolesUseCase, RoleManagementUseCase {

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

  @Override
  @Transactional(readOnly = true)
  public Role detail(UUID roleId) {
    return roleRepositoryPort
        .findById(roleId)
        .orElseThrow(() -> new AuthDomainException(AuthErrorCode.ROLE_NOT_FOUND));
  }

  @Override
  @Transactional
  public Role create(CreateRoleCommand command) {
    String normalizedCode =
        command.code() == null ? null : command.code().trim().toUpperCase(java.util.Locale.ROOT);
    if (normalizedCode == null || normalizedCode.isBlank()) {
      throw new IllegalArgumentException("Role code is required");
    }
    if (roleRepositoryPort.findByCode(normalizedCode).isPresent()) {
      throw new AuthDomainException(AuthErrorCode.ROLE_CODE_ALREADY_EXISTS);
    }
    Instant now = Instant.now(clock);
    Role saved =
        roleRepositoryPort.save(
            Role.create(normalizedCode, command.name(), command.description(), now));
    roleRepositoryPort.replacePermissions(
        saved.getId(), permissionsFor(saved.getId(), command.permissions()), now);
    publishAfterCommit(audit(command.actorId(), saved.getId(), OperationType.CREATE, now));
    return detail(saved.getId());
  }

  @Override
  @Transactional
  public Role update(UpdateRoleCommand command) {
    Role role = detail(command.roleId());
    String normalizedCode =
        command.code() == null ? null : command.code().trim().toUpperCase(java.util.Locale.ROOT);
    roleRepositoryPort
        .findByCode(normalizedCode)
        .filter(existing -> !existing.getId().equals(command.roleId()))
        .ifPresent(
            existing -> {
              throw new AuthDomainException(AuthErrorCode.ROLE_CODE_ALREADY_EXISTS);
            });
    try {
      role.update(normalizedCode, command.name(), command.description());
    } catch (IllegalStateException exception) {
      throw new AuthDomainException(AuthErrorCode.ROLE_IS_CONST);
    }
    Set<RolePermission> newPermissions = permissionsFor(command.roleId(), command.permissions());
    if ("ADMIN".equals(role.getCode()) && !isAdminPermissionSet(newPermissions)) {
      throw new AuthDomainException(AuthErrorCode.LAST_ACTIVE_ADMIN);
    }
    Instant now = Instant.now(clock);
    roleRepositoryPort.save(role);
    roleRepositoryPort.replacePermissions(command.roleId(), newPermissions, now);
    invalidateUsersFor(roleAndDescendants(command.roleId()));
    publishAfterCommit(
        audit(command.actorId(), command.roleId(), OperationType.ROLE_PERMISSION_UPDATED, now));
    return detail(command.roleId());
  }

  @Override
  @Transactional
  public Role setInheritance(SetRoleInheritanceCommand command) {
    Role child = detail(command.roleId());
    if (child.isConst()) {
      throw new AuthDomainException(AuthErrorCode.ROLE_IS_CONST);
    }
    Map<UUID, Role> roles = rolesById();
    if (command.roleInheritedId() != null && !roles.containsKey(command.roleInheritedId())) {
      throw new AuthDomainException(AuthErrorCode.ROLE_NOT_FOUND);
    }
    for (UUID cursor = command.roleInheritedId(); cursor != null; ) {
      if (command.roleId().equals(cursor)) {
        throw new AuthDomainException(AuthErrorCode.ROLE_INHERITANCE_CYCLE);
      }
      Role current = roles.get(cursor);
      cursor = current == null ? null : current.getRoleInheritedId();
    }
    child.setInheritedRole(command.roleInheritedId());
    Instant now = Instant.now(clock);
    roleRepositoryPort.save(child);
    invalidateUsersFor(roleAndDescendants(command.roleId()));
    publishAfterCommit(
        audit(command.actorId(), command.roleId(), OperationType.ROLE_INHERITANCE_UPDATED, now));
    return detail(command.roleId());
  }

  @Override
  @Transactional
  public Role activate(RoleActionCommand command) {
    Role role = detail(command.roleId());
    if (role.isActive()) {
      return role;
    }
    role.activate();
    Instant now = Instant.now(clock);
    Role saved = roleRepositoryPort.save(role);
    invalidateUsersFor(roleAndDescendants(command.roleId()));
    publishAfterCommit(audit(command.actorId(), command.roleId(), OperationType.ACTIVATED, now));
    return saved;
  }

  @Override
  @Transactional
  public Role inactivate(RoleActionCommand command) {
    Role role = detail(command.roleId());
    if ("ADMIN".equals(role.getCode())) {
      throw new AuthDomainException(AuthErrorCode.LAST_ACTIVE_ADMIN);
    }
    if (!role.isActive()) {
      return role;
    }
    role.inactivate();
    Instant now = Instant.now(clock);
    Role saved = roleRepositoryPort.save(role);
    invalidateUsersFor(roleAndDescendants(command.roleId()));
    publishAfterCommit(audit(command.actorId(), command.roleId(), OperationType.DEACTIVATED, now));
    return saved;
  }

  @Override
  @Transactional
  public void delete(RoleActionCommand command) {
    Role role = detail(command.roleId());
    if (role.isConst()) {
      throw new AuthDomainException(AuthErrorCode.ROLE_IS_CONST);
    }
    if (role.isActive()) {
      throw new AuthDomainException(AuthErrorCode.ROLE_IS_ACTIVE);
    }
    if (!roleRepositoryPort.findActiveUserIdsByRoleIds(Set.of(command.roleId())).isEmpty()) {
      throw new AuthDomainException(AuthErrorCode.ROLE_STILL_ASSIGNED);
    }
    Instant now = Instant.now(clock);
    role.delete(now);
    roleRepositoryPort.save(role);
    publishAfterCommit(audit(command.actorId(), command.roleId(), OperationType.DELETE, now));
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
}
