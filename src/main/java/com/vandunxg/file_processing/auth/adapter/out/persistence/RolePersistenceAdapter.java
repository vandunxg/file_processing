package com.vandunxg.file_processing.auth.adapter.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.JpaRolePermissionRepository;
import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.JpaRoleRepository;
import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.RolePermissionEntity;
import com.vandunxg.file_processing.auth.adapter.out.persistence.mapper.RolePersistenceMapper;
import com.vandunxg.file_processing.auth.application.port.out.RoleRepositoryPort;
import com.vandunxg.file_processing.auth.domain.exception.AuthDomainException;
import com.vandunxg.file_processing.auth.domain.exception.AuthErrorCode;
import com.vandunxg.file_processing.auth.domain.model.Role;
import com.vandunxg.file_processing.auth.domain.model.RolePermission;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-ROLE-PERSISTENCE")
public class RolePersistenceAdapter implements RoleRepositoryPort {

  private final JpaRoleRepository jpaRoleRepository;
  private final JpaRolePermissionRepository jpaRolePermissionRepository;
  private final RolePersistenceMapper rolePersistenceMapper;

  @Override
  public Optional<Role> findByCode(String code) {
    return jpaRoleRepository.findByCodeAndDeletedAtIsNull(code).map(this::toDomain);
  }

  @Override
  public Optional<Role> findById(UUID id) {
    return jpaRoleRepository.findByIdAndDeletedAtIsNull(id).map(this::toDomain);
  }

  @Override
  public List<Role> findByIds(Set<UUID> ids) {
    if (ids == null || ids.isEmpty()) {
      return List.of();
    }
    return toDomains(jpaRoleRepository.findByIdInAndDeletedAtIsNull(ids));
  }

  @Override
  public List<Role> findAll() {
    return toDomains(jpaRoleRepository.findAllByDeletedAtIsNull());
  }

  @Override
  public Role save(Role role) {
    return toDomain(jpaRoleRepository.saveAndFlush(rolePersistenceMapper.toEntity(role)));
  }

  @Override
  public Role lockAdminRole() {
    return jpaRoleRepository
        .findWithLockByCodeAndDeletedAtIsNull("ADMIN")
        .map(this::toDomain)
        .orElseThrow(() -> new AuthDomainException(AuthErrorCode.ROLE_NOT_FOUND));
  }

  @Override
  public void replacePermissions(UUID roleId, Collection<RolePermission> permissions, Instant now) {
    List<RolePermissionEntity> existing =
        jpaRolePermissionRepository.findByRoleIdAndDeletedAtIsNull(roleId);
    existing.forEach(permission -> permission.setDeletedAt(now));
    jpaRolePermissionRepository.saveAll(existing);
    jpaRolePermissionRepository.saveAll(
        permissions.stream().map(this::toEntity).collect(Collectors.toList()));
  }

  @Override
  public List<UUID> findActiveUserIdsByRoleIds(Set<UUID> roleIds) {
    return roleIds == null || roleIds.isEmpty()
        ? List.of()
        : jpaRoleRepository.findActiveUserIdsByRoleIds(roleIds);
  }

  private Role toDomain(
      com.vandunxg.file_processing.auth.adapter.out.persistence.entity.RoleEntity entity) {
    return toDomains(List.of(entity)).getFirst();
  }

  private List<Role> toDomains(
      List<com.vandunxg.file_processing.auth.adapter.out.persistence.entity.RoleEntity> entities) {
    List<Role> roles = rolePersistenceMapper.toDomain(entities);
    if (roles.isEmpty()) {
      return roles;
    }
    Map<UUID, Set<RolePermission>> permissionsByRole =
        jpaRolePermissionRepository
            .findByRoleIdInAndDeletedAtIsNull(
                roles.stream().map(Role::getId).collect(Collectors.toSet()))
            .stream()
            .map(this::toDomain)
            .collect(Collectors.groupingBy(RolePermission::getRoleId, Collectors.toSet()));
    roles.forEach(role -> role.enrichPermissions(permissionsByRole.get(role.getId())));
    return roles;
  }

  private RolePermission toDomain(RolePermissionEntity entity) {
    return RolePermission.builder()
        .id(entity.getId())
        .roleId(entity.getRoleId())
        .resourceCode(entity.getResourceCode())
        .action(entity.getAction())
        .resourceGroup(entity.getResourceGroup())
        .deletedAt(entity.getDeletedAt())
        .build();
  }

  private RolePermissionEntity toEntity(RolePermission permission) {
    RolePermissionEntity entity = new RolePermissionEntity();
    entity.setId(permission.getId());
    entity.setRoleId(permission.getRoleId());
    entity.setResourceCode(permission.getResourceCode());
    entity.setAction(permission.getAction());
    entity.setResourceGroup(permission.getResourceGroup());
    entity.setDeletedAt(permission.getDeletedAt());
    return entity;
  }
}
