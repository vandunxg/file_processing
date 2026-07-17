package com.vandunxg.file_processing.auth.adapter.out.persistence;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.JpaEmailVerificationTokenRepository;
import com.vandunxg.file_processing.auth.adapter.out.persistence.mapper.EmailVerificationTokenPersistenceMapper;
import com.vandunxg.file_processing.auth.application.port.out.EmailVerificationTokenRepositoryPort;
import com.vandunxg.file_processing.auth.domain.model.EmailVerificationToken;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-EMAIL-VERIFICATION-TOKEN-PERSISTENCE")
public class EmailVerificationTokenPersistenceAdapter
    implements EmailVerificationTokenRepositoryPort {

  private final JpaEmailVerificationTokenRepository jpaEmailVerificationTokenRepository;
  private final EmailVerificationTokenPersistenceMapper emailVerificationTokenPersistenceMapper;

  @Override
  public EmailVerificationToken save(EmailVerificationToken token) {
    log.debug("[save] persisting email verification token userId={}", token.getUserId());
    var saved =
        jpaEmailVerificationTokenRepository.save(
            emailVerificationTokenPersistenceMapper.toEntity(token));
    log.info("[save] persisted email verification token id={}", saved.getId());
    return emailVerificationTokenPersistenceMapper.toDomain(saved);
  }

  @Override
  public Optional<EmailVerificationToken> findByTokenHashForUpdate(String tokenHash) {
    return jpaEmailVerificationTokenRepository
        .findByTokenHashForUpdate(tokenHash)
        .map(emailVerificationTokenPersistenceMapper::toDomain);
  }

  @Override
  @Transactional
  public void invalidateAllForUser(UUID userId, Instant now) {
    int updated = jpaEmailVerificationTokenRepository.invalidateAllForUser(userId, now);
    log.info("[invalidateAllForUser] invalidated tokens userId={} count={}", userId, updated);
  }
}
