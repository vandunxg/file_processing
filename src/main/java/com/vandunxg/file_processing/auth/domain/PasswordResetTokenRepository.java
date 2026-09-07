package com.vandunxg.file_processing.auth.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.vandunxg.file_processing.auth.domain.model.PasswordResetToken;

public interface PasswordResetTokenRepository {

  PasswordResetToken save(PasswordResetToken token);

  Optional<PasswordResetToken> findByTokenHashForUpdate(String tokenHash);

  void invalidateAllForUser(UUID userId, Instant now);

  int deleteExpired(Instant now, int limit);
}
