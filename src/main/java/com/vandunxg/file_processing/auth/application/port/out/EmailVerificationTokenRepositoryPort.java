package com.vandunxg.file_processing.auth.application.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.vandunxg.file_processing.auth.domain.model.EmailVerificationToken;

public interface EmailVerificationTokenRepositoryPort {

  EmailVerificationToken save(EmailVerificationToken token);

  /** Pessimistic write lock. */
  Optional<EmailVerificationToken> findByTokenHashForUpdate(String tokenHash);

  /** Bulk: used_at = now WHERE user_id=? AND used_at IS NULL. */
  void invalidateAllForUser(UUID userId, Instant now);
}
