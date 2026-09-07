package com.vandunxg.file_processing.auth.infrastructure.persistence;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import com.vandunxg.file_processing.auth.application.capability.RoleSearchRepository;
import com.vandunxg.file_processing.auth.application.exception.AuthErrorCode;
import com.vandunxg.file_processing.auth.application.exception.AuthException;
import com.vandunxg.file_processing.auth.application.query.RoleSearchQuery;
import com.vandunxg.file_processing.auth.domain.RoleRepository;
import com.vandunxg.file_processing.auth.domain.model.Role;
import com.vandunxg.file_processing.auth.domain.model.RolePermission;
import com.vandunxg.file_processing.auth.infrastructure.persistence.entity.RoleEntity;
import com.vandunxg.file_processing.auth.infrastructure.persistence.entity.RolePermissionEntity;
import com.vandunxg.file_processing.auth.infrastructure.persistence.mapper.RolePermissionPersistenceMapper;
import com.vandunxg.file_processing.auth.infrastructure.persistence.mapper.RolePersistenceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-ROLE-PERSISTENCE")
public class JpaRoleRepository implements RoleRepository, RoleSearchRepository {

  private final RoleEntityRepository roleEntityRepository;
  private final RolePermissionEntityRepository rolePermissionEntityRepository;
  private final RolePersistenceMapper rolePersistenceMapper;
  private final RolePermissionPersistenceMapper rolePermissionPersistenceMapper;

  @Override
  public Optional<Role> findByCode(String code) {
    return roleEntityRepository.findByCodeAndDeletedAtIsNull(code).map(this::toDomain);
  }

  @Override
  public Optional<Role> findById(UUID id) {
    return roleEntityRepository.findByIdAndDeletedAtIsNull(id).map(this::toDomain);
  }

  @Override
  public List<Role> findByIds(Set<UUID> ids) {
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    return toDomains(roleEntityRepository.findByIdInAndDeletedAtIsNull(ids));
  }

  @Override
  public List<Role> findAll() {
    return toDomains(roleEntityRepository.findAllByDeletedAtIsNull());
  }

  @Override
  public Role save(Role role) {
    return toDomain(roleEntityRepository.saveAndFlush(rolePersistenceMapper.toEntity(role)));
  }

  @Override
  public Role lockAdminRole() {
    return roleEntityRepository
        .findWithLockByCodeAndDeletedAtIsNull("ADMIN")
        .map(this::toDomain)
        .orElseThrow(() -> new AuthException(AuthErrorCode.ROLE_NOT_FOUND));
  }

  @Override
  public void replacePermissions(UUID roleId, Collection<RolePermission> permissions, Instant now) {
    List<RolePermissionEntity> existing =
        rolePermissionEntityRepository.findByRoleIdAndDeletedAtIsNull(roleId);
    existing.forEach(permission -> permission.setDeletedAt(now));
    rolePermissionEntityRepository.saveAll(existing);
    rolePermissionEntityRepository.saveAll(
        permissions.stream().map(rolePermissionPersistenceMapper::toEntity).toList());
  }

  @Override
  public List<UUID> findActiveUserIdsByRoleIds(Set<UUID> roleIds) {
    return roleIds == null || roleIds.isEmpty()
        ? List.of()
        : roleEntityRepository.findActiveUserIdsByRoleIds(roleIds);
  }

  @Override
  public Long count(RoleSearchQuery query) {
    return roleEntityRepository.count(query);
  }

  @Override
  public List<Role> search(RoleSearchQuery query) {

    var entities = roleEntityRepository.search(query);

    return toDomains(entities);
  }

  private Role toDomain(RoleEntity entity) {
    return toDomains(List.of(entity)).getFirst();
  }

  private List<Role> toDomains(List<RoleEntity> entities) {
    List<Role> roles = rolePersistenceMapper.toDomain(entities);
    if (roles.isEmpty()) {
      return roles;
    }
    Map<UUID, Set<RolePermission>> permissionsByRole =
        rolePermissionEntityRepository
            .findByRoleIdInAndDeletedAtIsNull(
                roles.stream().map(Role::getId).collect(Collectors.toSet()))
            .stream()
            .map(rolePermissionPersistenceMapper::toDomain)
            .collect(Collectors.groupingBy(RolePermission::getRoleId, Collectors.toSet()));
    roles.forEach(role -> role.enrichPermissions(permissionsByRole.get(role.getId())));
    return roles;
  }
}
