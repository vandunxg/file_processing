package com.vandunxg.file_processing.auth.adapter.out.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.JpaPasswordResetTokenRepository;
import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.PasswordResetTokenEntity;
import com.vandunxg.file_processing.auth.application.port.out.PasswordResetTokenRepositoryPort;
import com.vandunxg.file_processing.auth.domain.model.PasswordResetToken;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PasswordResetTokenPersistenceAdapter implements PasswordResetTokenRepositoryPort {

  private final JpaPasswordResetTokenRepository repository;

  @Override
  public PasswordResetToken save(PasswordResetToken token) {
    return toDomain(repository.saveAndFlush(toEntity(token)));
  }

  @Override
  public Optional<PasswordResetToken> findByTokenHashForUpdate(String tokenHash) {
    return repository.findByTokenHashForUpdate(tokenHash).map(this::toDomain);
  }

  @Override
  public void invalidateAllForUser(UUID userId, Instant now) {
    repository.invalidateAllForUser(userId, now);
  }

  private PasswordResetTokenEntity toEntity(PasswordResetToken token) {
    return new PasswordResetTokenEntity(
        token.getId(),
        token.getUserId(),
        token.getTokenHash(),
        token.getIssuedAt(),
        token.getExpiresAt(),
        token.getUsedAt(),
        token.getIpAddressHash());
  }

  private PasswordResetToken toDomain(PasswordResetTokenEntity entity) {
    return PasswordResetToken.builder()
        .id(entity.getId())
        .userId(entity.getUserId())
        .tokenHash(entity.getTokenHash())
        .issuedAt(entity.getIssuedAt())
        .expiresAt(entity.getExpiresAt())
        .usedAt(entity.getUsedAt())
        .ipAddressHash(entity.getIpAddressHash())
        .build();
  }
}
