package com.vandunxg.file_processing.auth.application.service;

import java.time.Clock;
import java.time.Instant;

import com.vandunxg.file_processing.auth.application.AuthProperties;
import com.vandunxg.file_processing.auth.domain.PasswordResetTokenRepository;
import com.vandunxg.file_processing.auth.domain.SessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthCleanupService {

  private final PasswordResetTokenRepository passwordResetTokenRepository;
  private final SessionRepository sessionRepository;
  private final AuthProperties authProperties;
  private final Clock clock;

  @Transactional
  public CleanupResult cleanExpired() {
    Instant now = Instant.now(clock);
    int batchSize = authProperties.cleanup().batchSize();
    int passwordResetTokens = passwordResetTokenRepository.deleteExpired(now, batchSize);
    int refreshFamilies = sessionRepository.deleteExpiredOrRevoked(now, batchSize);
    // Email-verification tokens are Redis keys with a TTL and expire without a key scan.
    return new CleanupResult(passwordResetTokens, refreshFamilies);
  }

  public record CleanupResult(int passwordResetTokens, int refreshFamilies) {

    public boolean isEmpty() {
      return passwordResetTokens == 0 && refreshFamilies == 0;
    }
  }
}
