package com.vandunxg.file_processing.auth.infrastructure.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.vandunxg.file_processing.auth.domain.PasswordResetTokenRepository;
import com.vandunxg.file_processing.auth.domain.model.PasswordResetToken;
import com.vandunxg.file_processing.auth.infrastructure.persistence.mapper.PasswordResetTokenPersistenceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class JpaPasswordResetTokenRepository implements PasswordResetTokenRepository {

  private final PasswordResetTokenEntityRepository repository;
  private final PasswordResetTokenPersistenceMapper mapper;

  @Override
  public PasswordResetToken save(PasswordResetToken token) {
    return mapper.toDomain(repository.saveAndFlush(mapper.toEntity(token)));
  }

  @Override
  public Optional<PasswordResetToken> findByTokenHashForUpdate(String tokenHash) {
    return repository.findByTokenHashForUpdate(tokenHash).map(mapper::toDomain);
  }

  @Override
  public void invalidateAllForUser(UUID userId, Instant now) {
    repository.invalidateAllForUser(userId, now);
  }

  @Override
  @Transactional
  public int deleteExpired(Instant now, int limit) {
    return repository.deleteExpired(now, limit);
  }
}
