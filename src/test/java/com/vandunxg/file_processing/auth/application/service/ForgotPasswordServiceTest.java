package com.vandunxg.file_processing.auth.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

import com.vandunxg.common.utils.HashUtils;
import com.vandunxg.file_processing.auth.adapter.out.metrics.AuthMetrics;
import com.vandunxg.file_processing.auth.application.command.ForgotPasswordCommand;
import com.vandunxg.file_processing.auth.application.port.out.AuditLogEventPublisherPort;
import com.vandunxg.file_processing.auth.application.port.out.AuthThrottlePort;
import com.vandunxg.file_processing.auth.application.port.out.EmailSenderPort;
import com.vandunxg.file_processing.auth.application.port.out.PasswordResetTokenRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.UserRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.VerificationTokenGeneratorPort;
import com.vandunxg.file_processing.auth.domain.exception.AuthDomainException;
import com.vandunxg.file_processing.auth.domain.exception.AuthErrorCode;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import com.vandunxg.file_processing.auth.domain.model.OperationType;
import com.vandunxg.file_processing.auth.domain.model.PasswordResetToken;
import com.vandunxg.file_processing.auth.domain.model.User;
import com.vandunxg.file_processing.auth.domain.model.UserStatus;
import com.vandunxg.file_processing.testsupport.AuthPropertiesFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class ForgotPasswordServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-20T10:15:30Z");
  private static final String IDENTIFIER = "operator01@example.com";
  private static final String RAW_TOKEN = "new-password-reset-token";

  @Mock private AuthThrottlePort throttlePort;
  @Mock private UserRepositoryPort userRepositoryPort;
  @Mock private PasswordResetTokenRepositoryPort tokenRepositoryPort;
  @Mock private VerificationTokenGeneratorPort tokenGeneratorPort;
  @Mock private EmailSenderPort emailSenderPort;
  @Mock private AuditLogEventPublisherPort auditLogEventPublisherPort;
  @Mock private AuthMetrics authMetrics;

  private ForgotPasswordService forgotPasswordService;

  @BeforeEach
  void setUp() {
    forgotPasswordService =
        new ForgotPasswordService(
            throttlePort,
            userRepositoryPort,
            tokenRepositoryPort,
            tokenGeneratorPort,
            emailSenderPort,
            auditLogEventPublisherPort,
            authMetrics,
            AuthPropertiesFixture.defaults(),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @AfterEach
  void tearDown() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void requestInvalidatesOldTokensPersistsSha256TokenAndEmailsOnlyAfterCommit() {
    UUID userId = UUID.randomUUID();
    User user = user(userId, UserStatus.ACTIVE);
    when(throttlePort.tryConsume(anyString(), anyInt(), any())).thenReturn(true);
    when(userRepositoryPort.findByNormalizedIdentifier(IDENTIFIER)).thenReturn(Optional.of(user));
    when(tokenGeneratorPort.generate()).thenReturn(RAW_TOKEN);
    when(tokenRepositoryPort.save(any(PasswordResetToken.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    TransactionSynchronizationManager.initSynchronization();

    forgotPasswordService.request(
        ForgotPasswordCommand.builder()
            .identifier("  " + IDENTIFIER + "  ")
            .ipAddress("203.0.113.5")
            .build());

    verify(tokenRepositoryPort).invalidateAllForUser(userId, NOW);
    ArgumentCaptor<PasswordResetToken> tokenCaptor =
        ArgumentCaptor.forClass(PasswordResetToken.class);
    verify(tokenRepositoryPort).save(tokenCaptor.capture());
    assertThat(tokenCaptor.getValue().getTokenHash())
        .isEqualTo(HashUtils.sha256(RAW_TOKEN.getBytes(StandardCharsets.UTF_8)));
    assertThat(tokenCaptor.getValue().getExpiresAt()).isEqualTo(NOW.plusSeconds(900));
    verifyNoInteractions(emailSenderPort, auditLogEventPublisherPort);

    new ArrayList<>(TransactionSynchronizationManager.getSynchronizations())
        .forEach(TransactionSynchronization::afterCommit);

    verify(emailSenderPort)
        .sendPasswordResetEmail(
            "operator01@example.com",
            "Operator One",
            "https://app.example.com/reset-password?token=" + RAW_TOKEN);
    ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogEventPublisherPort).publish(auditCaptor.capture());
    assertThat(auditCaptor.getValue().getOperation())
        .isEqualTo(OperationType.PASSWORD_RESET_REQUESTED);
    verify(authMetrics).passwordResetRequested();
  }

  @Test
  void requestReturnsUserNotFoundForUnknownIdentifierAfterBothRateLimitsAllow() {
    when(throttlePort.tryConsume(anyString(), anyInt(), any())).thenReturn(true);
    when(userRepositoryPort.findByNormalizedIdentifier(IDENTIFIER)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                forgotPasswordService.request(
                    ForgotPasswordCommand.builder()
                        .identifier(IDENTIFIER)
                        .ipAddress("203.0.113.5")
                        .build()))
        .isInstanceOf(AuthDomainException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.USER_NOT_FOUND);

    verifyNoInteractions(tokenRepositoryPort, tokenGeneratorPort, emailSenderPort);
  }

  @Test
  void requestIsNoContentForDisabledUserAndInvalidatesOldTokensWithoutIssuingAnother() {
    UUID userId = UUID.randomUUID();
    when(throttlePort.tryConsume(anyString(), anyInt(), any())).thenReturn(true);
    when(userRepositoryPort.findByNormalizedIdentifier(IDENTIFIER))
        .thenReturn(Optional.of(user(userId, UserStatus.DISABLED)));

    forgotPasswordService.request(
        ForgotPasswordCommand.builder().identifier(IDENTIFIER).ipAddress("203.0.113.5").build());

    verify(tokenRepositoryPort).invalidateAllForUser(userId, NOW);
    verify(tokenRepositoryPort, never()).save(any());
    verifyNoInteractions(tokenGeneratorPort, emailSenderPort, auditLogEventPublisherPort);
  }

  @Test
  void requestRejectsWhenTheNormalizedIdentifierRateLimitIsExceededBeforeLookup() {
    when(throttlePort.tryConsume(anyString(), anyInt(), any())).thenReturn(true, false);

    assertThatThrownBy(
            () ->
                forgotPasswordService.request(
                    ForgotPasswordCommand.builder()
                        .identifier(IDENTIFIER)
                        .ipAddress("203.0.113.5")
                        .build()))
        .isInstanceOf(AuthDomainException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.AUTH_RATE_LIMITED);

    verifyNoInteractions(
        userRepositoryPort,
        tokenRepositoryPort,
        tokenGeneratorPort,
        emailSenderPort,
        auditLogEventPublisherPort);
    verify(authMetrics).forgotPasswordRateLimited();
  }

  private static User user(UUID id, UserStatus status) {
    return User.builder()
        .id(id)
        .username("operator01")
        .normalizedUsername("operator01")
        .email(IDENTIFIER)
        .normalizedEmail(IDENTIFIER)
        .displayName("Operator One")
        .passwordHash("{bcrypt}current")
        .status(status)
        .credentialVersion(1)
        .build();
  }
}
