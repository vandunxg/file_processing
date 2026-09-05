package com.vandunxg.file_processing.auth.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;

import com.vandunxg.file_processing.auth.application.AuthProperties;
import com.vandunxg.file_processing.auth.domain.PasswordResetTokenRepository;
import com.vandunxg.file_processing.auth.domain.SessionRepository;
import com.vandunxg.file_processing.testsupport.AuthPropertiesFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuthCleanupServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-20T10:15:30Z");
  private static final Clock CLOCK = Clock.fixed(NOW, java.time.ZoneOffset.UTC);

  @Mock private PasswordResetTokenRepository passwordResetTokenRepository;
  @Mock private SessionRepository sessionRepository;

  @Test
  void removesOneConfiguredBatchOfExpiredPersistedArtifacts() {
    AuthProperties properties = AuthPropertiesFixture.defaults();
    int batchSize = properties.cleanup().batchSize();
    when(passwordResetTokenRepository.deleteExpired(NOW, batchSize)).thenReturn(2);
    when(sessionRepository.deleteExpiredOrRevoked(NOW, batchSize)).thenReturn(3);
    AuthCleanupService service =
        new AuthCleanupService(passwordResetTokenRepository, sessionRepository, properties, CLOCK);

    assertThat(service.cleanExpired()).isEqualTo(new AuthCleanupService.CleanupResult(2, 3));

    verify(passwordResetTokenRepository).deleteExpired(NOW, batchSize);
    verify(sessionRepository).deleteExpiredOrRevoked(NOW, batchSize);
  }
}
