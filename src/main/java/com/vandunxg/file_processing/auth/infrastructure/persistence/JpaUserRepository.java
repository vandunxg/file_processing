package com.vandunxg.file_processing.auth.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.vandunxg.common.utils.IdUtils;
import com.vandunxg.file_processing.auth.application.capability.UserSearchRepository;
import com.vandunxg.file_processing.auth.application.query.UserSearchQuery;
import com.vandunxg.file_processing.auth.domain.UserRepository;
import com.vandunxg.file_processing.auth.domain.model.Role;
import com.vandunxg.file_processing.auth.domain.model.User;
import com.vandunxg.file_processing.auth.domain.model.UserRole;
import com.vandunxg.file_processing.auth.infrastructure.persistence.entity.UserRoleAssociation;
import com.vandunxg.file_processing.auth.infrastructure.persistence.entity.UserRoleEntity;
import com.vandunxg.file_processing.auth.infrastructure.persistence.mapper.RolePersistenceMapper;
import com.vandunxg.file_processing.auth.infrastructure.persistence.mapper.UserPersistenceMapper;
import com.vandunxg.file_processing.auth.infrastructure.persistence.mapper.UserRolePersistenceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-USER-PERSISTENCE")
public class JpaUserRepository implements UserRepository, UserSearchRepository {

  private final UserEntityRepository userEntityRepository;
  private final UserPersistenceMapper userPersistenceMapper;
  private final RoleEntityRepository roleEntityRepository;
  private final RolePersistenceMapper rolePersistenceMapper;
  private final UserRoleEntityRepository userRoleEntityRepository;
  private final UserRolePersistenceMapper userRolePersistenceMapper;

  @Override
  public boolean existsAny() {
    return userEntityRepository.count() > 0;
  }

  @Override
  public boolean existsByNormalizedUsername(String normalizedUsername) {
    return userEntityRepository.existsByNormalizedUsernameAndDeletedAtIsNull(normalizedUsername);
  }

  @Override
  public boolean existsByNormalizedEmail(String normalizedEmail) {
    return userEntityRepository.existsByNormalizedEmailAndDeletedAtIsNull(normalizedEmail);
  }

  @Override
  public Optional<User> findById(UUID id) {
    return userEntityRepository
        .findByIdAndDeletedAtIsNull(id)
        .map(userPersistenceMapper::toDomain)
        .map(this::enrich);
  }

  @Override
  public Optional<User> findByIdForUpdate(UUID id) {
    return userEntityRepository
        .findWithLockByIdAndDeletedAtIsNull(id)
        .map(userPersistenceMapper::toDomain)
        .map(this::enrich);
  }

  @Override
  public List<User> findAll() {
    return enrichAll(
        userPersistenceMapper.toDomain(
            userEntityRepository.findAllByDeletedAtIsNullOrderByCreatedAtDesc()));
  }

  @Override
  public Long count(UserSearchQuery query) {
    return userEntityRepository.count(query);
  }

  @Override
  public List<User> search(UserSearchQuery query) {
    return enrichAll(userPersistenceMapper.toDomain(userEntityRepository.search(query)));
  }

  @Override
  public long countActiveAdmins() {
    return userEntityRepository.countActiveAdmins();
  }

  @Override
  public Optional<User> findByNormalizedIdentifier(String normalizedIdentifier) {
    if (normalizedIdentifier != null && normalizedIdentifier.contains("@")) {
      return userEntityRepository
          .findByNormalizedEmailAndDeletedAtIsNull(normalizedIdentifier)
          .map(userPersistenceMapper::toDomain)
          .map(this::enrich);
    }
    return userEntityRepository
        .findByNormalizedUsernameAndDeletedAtIsNull(normalizedIdentifier)
        .map(userPersistenceMapper::toDomain)
        .map(this::enrich);
  }

  @Override
  public Optional<Integer> findCredentialVersionById(UUID id) {
    return userEntityRepository.findCredentialVersionByIdAndDeletedAtIsNull(id);
  }

  @Override
  public User save(User user) {
    log.debug("[save] attempting to persist user userId={}", user.getId());
    // saveAndFlush is load-bearing: it forces the unique-constraint check (normalized_username /
    // normalized_email) to run synchronously inside this call, so callers can catch
    // DataIntegrityViolationException here rather than at transaction commit.
    var saved = userEntityRepository.saveAndFlush(userPersistenceMapper.toEntity(user));
    log.info("[save] persisted user userId={}", saved.getId());

    return this.enrich(userPersistenceMapper.toDomain(saved));
  }

  @Override
  public int bumpCredentialVersionFor(java.util.Collection<UUID> userIds) {
    if (userIds.isEmpty()) {
      return 0;
    }
    int updated = userEntityRepository.bumpCredentialVersionFor(userIds);
    log.info("[bumpCredentialVersionFor] invalidated credentials users={}", updated);
    return updated;
  }

  @Override
  public UserRole assignRole(UserRole userRole) {
    log.debug(
        "[assignRole] persisting user_role userId={} roleId={}",
        userRole.getUserId(),
        userRole.getRoleId());
    var saved = userRoleEntityRepository.save(userRolePersistenceMapper.toEntity(userRole));
    log.info("[assignRole] persisted user_role id={}", saved.getId());
    return userRolePersistenceMapper.toDomain(saved);
  }

  @Override
  public void replaceRoles(UUID userId, Set<UUID> roleIds, Instant now) {
    var existing = userRoleEntityRepository.findByUserIdAndDeletedAtIsNull(userId);
    existing.forEach(role -> role.setDeletedAt(now));
    userRoleEntityRepository.saveAll(existing);
    userRoleEntityRepository.saveAll(
        roleIds.stream()
            .map(
                roleId -> {
                  UserRoleEntity entity = new UserRoleEntity();
                  entity.setId(IdUtils.nextId());
                  entity.setUserId(userId);
                  entity.setRoleId(roleId);
                  return entity;
                })
            .toList());
  }

  private User enrich(User domain) {
    return enrichAll(List.of(domain)).getFirst();
  }

  // Batch-shaped so a future list/search use case can reuse this without a rewrite: one query
  // for the users, one query for all their roles, joined in memory — no per-user round trip.
  private List<User> enrichAll(List<User> domains) {
    if (domains.isEmpty()) {
      return domains;
    }
    var rolesByUserId =
        rolesByUserId(domains.stream().map(User::getId).collect(Collectors.toSet()));
    domains.forEach(
        domain -> domain.enrichRoles(rolesByUserId.getOrDefault(domain.getId(), Set.of())));
    return domains;
  }

  private Map<UUID, Set<Role>> rolesByUserId(Set<UUID> userIds) {
    return roleEntityRepository.findRolesByUserIds(userIds).stream()
        .collect(
            Collectors.groupingBy(
                UserRoleAssociation::userId,
                Collectors.mapping(
                    association -> rolePersistenceMapper.toDomain(association.role()),
                    Collectors.toSet())));
  }
}
