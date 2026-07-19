package com.vandunxg.file_processing.auth.adapter.out.persistence;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.JpaRoleRepository;
import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.JpaUserRepository;
import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.UserRoleAssociation;
import com.vandunxg.file_processing.auth.adapter.out.persistence.mapper.RolePersistenceMapper;
import com.vandunxg.file_processing.auth.adapter.out.persistence.mapper.UserPersistenceMapper;
import com.vandunxg.file_processing.auth.application.port.out.UserRepositoryPort;
import com.vandunxg.file_processing.auth.domain.model.Role;
import com.vandunxg.file_processing.auth.domain.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-USER-PERSISTENCE")
public class UserPersistenceAdapter implements UserRepositoryPort {

  private final JpaUserRepository jpaUserRepository;
  private final UserPersistenceMapper userPersistenceMapper;
  private final JpaRoleRepository jpaRoleRepository;
  private final RolePersistenceMapper rolePersistenceMapper;

  @Override
  public boolean existsByNormalizedUsername(String normalizedUsername) {
    return jpaUserRepository.existsByNormalizedUsernameAndDeletedAtIsNull(normalizedUsername);
  }

  @Override
  public boolean existsByNormalizedEmail(String normalizedEmail) {
    return jpaUserRepository.existsByNormalizedEmailAndDeletedAtIsNull(normalizedEmail);
  }

  @Override
  public Optional<User> findById(UUID id) {
    return jpaUserRepository
        .findByIdAndDeletedAtIsNull(id)
        .map(userPersistenceMapper::toDomain)
        .map(this::enrich);
  }

  @Override
  public Optional<User> findByNormalizedIdentifier(String normalizedIdentifier) {
    if (normalizedIdentifier != null && normalizedIdentifier.contains("@")) {
      return jpaUserRepository
          .findByNormalizedEmailAndDeletedAtIsNull(normalizedIdentifier)
          .map(userPersistenceMapper::toDomain)
          .map(this::enrich);
    }
    return jpaUserRepository
        .findByNormalizedUsernameAndDeletedAtIsNull(normalizedIdentifier)
        .map(userPersistenceMapper::toDomain)
        .map(this::enrich);
  }

  @Override
  public Optional<Integer> findCredentialVersionById(UUID id) {
    return jpaUserRepository.findCredentialVersionByIdAndDeletedAtIsNull(id);
  }

  @Override
  public User save(User user) {
    log.debug("[save] attempting to persist user userId={}", user.getId());
    // saveAndFlush is load-bearing: it forces the unique-constraint check (normalized_username /
    // normalized_email) to run synchronously inside this call, so callers can catch
    // DataIntegrityViolationException here rather than at transaction commit.
    var saved = jpaUserRepository.saveAndFlush(userPersistenceMapper.toEntity(user));
    log.info("[save] persisted user userId={}", saved.getId());

    return this.enrich(userPersistenceMapper.toDomain(saved));
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
    return jpaRoleRepository.findRolesByUserIds(userIds).stream()
        .collect(
            Collectors.groupingBy(
                UserRoleAssociation::userId,
                Collectors.mapping(
                    association -> rolePersistenceMapper.toDomain(association.role()),
                    Collectors.toSet())));
  }
}
