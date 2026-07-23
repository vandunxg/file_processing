package com.vandunxg.file_processing.auth.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;

import com.vandunxg.file_processing.auth.application.port.out.PasswordResetTokenRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.SessionRepositoryPort;
import com.vandunxg.file_processing.auth.configuration.AuthProperties;
import com.vandunxg.file_processing.testsupport.AuthPropertiesFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuthCleanupServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-20T10:15:30Z");
  private static final Clock CLOCK = Clock.fixed(NOW, java.time.ZoneOffset.UTC);

  @Mock private PasswordResetTokenRepositoryPort passwordResetTokenRepositoryPort;
  @Mock private SessionRepositoryPort sessionRepositoryPort;

  @Test
  void removesOneConfiguredBatchOfExpiredPersistedArtifacts() {
    AuthProperties properties = AuthPropertiesFixture.defaults();
    int batchSize = properties.cleanup().batchSize();
    when(passwordResetTokenRepositoryPort.deleteExpired(NOW, batchSize)).thenReturn(2);
    when(sessionRepositoryPort.deleteExpiredOrRevoked(NOW, batchSize)).thenReturn(3);
    AuthCleanupService service =
        new AuthCleanupService(
            passwordResetTokenRepositoryPort, sessionRepositoryPort, properties, CLOCK);

    assertThat(service.cleanExpired()).isEqualTo(new AuthCleanupService.CleanupResult(2, 3));

    verify(passwordResetTokenRepositoryPort).deleteExpired(NOW, batchSize);
    verify(sessionRepositoryPort).deleteExpiredOrRevoked(NOW, batchSize);
  }
}
