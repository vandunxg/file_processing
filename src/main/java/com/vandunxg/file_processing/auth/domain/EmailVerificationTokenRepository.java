package com.vandunxg.file_processing.auth.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.vandunxg.file_processing.auth.domain.model.EmailVerificationToken;

public interface EmailVerificationTokenRepository {

  EmailVerificationToken save(EmailVerificationToken token);

  /** Pessimistic write lock. */
  Optional<EmailVerificationToken> findByTokenHashForUpdate(String tokenHash);

  /** Bulk: used_at = now WHERE user_id=? AND used_at IS NULL. */
  void invalidateAllForUser(UUID userId, Instant now);
}
