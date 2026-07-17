package com.vandunxg.file_processing.auth.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;

import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.JpaUserRepository;
import com.vandunxg.file_processing.auth.adapter.out.persistence.mapper.UserPersistenceMapper;
import com.vandunxg.file_processing.auth.application.port.out.UserRepositoryPort;
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
    return jpaUserRepository.findByIdAndDeletedAtIsNull(id).map(userPersistenceMapper::toDomain);
  }

  @Override
  public Optional<User> findByNormalizedIdentifier(String normalizedIdentifier) {
    if (normalizedIdentifier != null && normalizedIdentifier.contains("@")) {
      return jpaUserRepository
          .findByNormalizedEmailAndDeletedAtIsNull(normalizedIdentifier)
          .map(userPersistenceMapper::toDomain);
    }
    return jpaUserRepository
        .findByNormalizedUsernameAndDeletedAtIsNull(normalizedIdentifier)
        .map(userPersistenceMapper::toDomain);
  }

  @Override
  public User save(User user) {
    log.debug("[save] attempting to persist user userId={}", user.getId());
    // saveAndFlush is load-bearing: it forces the unique-constraint check (normalized_username /
    // normalized_email) to run synchronously inside this call, so callers can catch
    // DataIntegrityViolationException here rather than at transaction commit.
    var saved = jpaUserRepository.saveAndFlush(userPersistenceMapper.toEntity(user));
    log.info("[save] persisted user userId={}", saved.getId());
    return userPersistenceMapper.toDomain(saved);
  }
}
