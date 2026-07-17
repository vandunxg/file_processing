package com.vandunxg.file_processing.auth.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.vandunxg.common.utils.HashUtils;
import com.vandunxg.file_processing.auth.application.command.ResendVerificationEmailCommand;
import com.vandunxg.file_processing.auth.application.port.out.AuditLogPort;
import com.vandunxg.file_processing.auth.application.port.out.EmailSenderPort;
import com.vandunxg.file_processing.auth.application.port.out.EmailVerificationTokenRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.RegisterThrottlePort;
import com.vandunxg.file_processing.auth.application.port.out.UserRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.VerificationTokenGeneratorPort;
import com.vandunxg.file_processing.auth.configuration.AuthProperties;
import com.vandunxg.file_processing.auth.domain.exception.AuthDomainException;
import com.vandunxg.file_processing.auth.domain.exception.AuthErrorCode;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import com.vandunxg.file_processing.auth.domain.model.EmailVerificationToken;
import com.vandunxg.file_processing.auth.domain.model.OperationType;
import com.vandunxg.file_processing.auth.domain.model.User;
import com.vandunxg.file_processing.auth.domain.model.UserStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class ResendVerificationEmailServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-17T10:15:30Z");

  @Mock private RegisterThrottlePort throttlePort;
  @Mock private UserRepositoryPort userRepositoryPort;
  @Mock private EmailVerificationTokenRepositoryPort tokenRepositoryPort;
  @Mock private AuditLogPort auditLogPort;
  @Mock private VerificationTokenGeneratorPort tokenGeneratorPort;
  @Mock private EmailSenderPort emailSenderPort;

  private ResendVerificationEmailService resendVerificationEmailService;

  @BeforeEach
  void setUp() {
    AuthProperties authProperties =
        new AuthProperties(
            new AuthProperties.Password("bcrypt", 10, 8, 128),
            new AuthProperties.Register(5),
            new AuthProperties.EmailVerification(
                Duration.ofMinutes(15), "https://app.example.com/verify", 5));
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    resendVerificationEmailService =
        new ResendVerificationEmailService(
            throttlePort,
            userRepositoryPort,
            tokenRepositoryPort,
            auditLogPort,
            tokenGeneratorPort,
            emailSenderPort,
            authProperties,
            clock);
  }

  @AfterEach
  void tearDown() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void resendIsSilentNoOpWhenIdentifierUnknown() {
    ResendVerificationEmailCommand command = command("nobody@example.com");
    givenThrottleAllows();
    when(userRepositoryPort.findByNormalizedIdentifier("nobody@example.com"))
        .thenReturn(Optional.empty());

    resendVerificationEmailService.resend(command);

    verifyNoInteractions(tokenRepositoryPort, auditLogPort, emailSenderPort);
  }

  @Test
  void resendIsSilentNoOpWhenAccountAlreadyActive() {
    ResendVerificationEmailCommand command = command("operator1@example.com");
    givenThrottleAllows();
    when(userRepositoryPort.findByNormalizedIdentifier("operator1@example.com"))
        .thenReturn(Optional.of(activeUser(UUID.randomUUID())));

    resendVerificationEmailService.resend(command);

    verifyNoInteractions(tokenRepositoryPort, auditLogPort, emailSenderPort);
  }

  @Test
  void resendInvalidatesOldTokensIssuesNewTokenRecordsAuditAndSchedulesEmailWhenAccountPending() {
    UUID userId = UUID.randomUUID();
    User pendingUser = pendingUser(userId);
    ResendVerificationEmailCommand command = command("operator1@example.com");
    givenThrottleAllows();
    when(userRepositoryPort.findByNormalizedIdentifier("operator1@example.com"))
        .thenReturn(Optional.of(pendingUser));
    when(tokenGeneratorPort.generate()).thenReturn("new-raw-token");
    when(tokenRepositoryPort.save(any(EmailVerificationToken.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    TransactionSynchronizationManager.initSynchronization();

    resendVerificationEmailService.resend(command);

    verify(tokenRepositoryPort).invalidateAllForUser(userId, NOW);

    ArgumentCaptor<EmailVerificationToken> tokenCaptor =
        ArgumentCaptor.forClass(EmailVerificationToken.class);
    verify(tokenRepositoryPort).save(tokenCaptor.capture());
    String expectedHash = HashUtils.sha256("new-raw-token".getBytes(StandardCharsets.UTF_8));
    assertThat(tokenCaptor.getValue().getTokenHash()).isEqualTo(expectedHash);
    assertThat(tokenCaptor.getValue().getTokenHash()).isNotEqualTo("new-raw-token");
    assertThat(tokenCaptor.getValue().getUserId()).isEqualTo(userId);

    ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogPort).record(auditCaptor.capture());
    assertThat(auditCaptor.getValue().getOperation())
        .isEqualTo(OperationType.EMAIL_VERIFICATION_REQUESTED);
    assertThat(auditCaptor.getValue().getObjectId()).isEqualTo(userId);
    assertThat(auditCaptor.getValue().getChangedBy()).isEqualTo(userId);

    verifyNoInteractions(emailSenderPort);

    new ArrayList<>(TransactionSynchronizationManager.getSynchronizations())
        .forEach(TransactionSynchronization::afterCommit);

    verify(emailSenderPort)
        .sendVerificationEmail(
            "operator1@example.com",
            "Operator One",
            "https://app.example.com/verify?token=new-raw-token");
  }

  @Test
  void resendThrowsRateLimitedBeforeIdentifierLookupWhenThrottleExceeded() {
    ResendVerificationEmailCommand command = command("operator1@example.com");
    when(throttlePort.tryConsume(eq("resend:" + command.getIpAddress()), eq(5)))
        .thenReturn(false);

    assertThatThrownBy(() -> resendVerificationEmailService.resend(command))
        .isInstanceOf(AuthDomainException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.AUTH_RATE_LIMITED);

    verifyNoInteractions(
        userRepositoryPort, tokenRepositoryPort, auditLogPort, tokenGeneratorPort, emailSenderPort);
  }

  private void givenThrottleAllows() {
    when(throttlePort.tryConsume(anyString(), eq(5))).thenReturn(true);
  }

  private static ResendVerificationEmailCommand command(String identifier) {
    return ResendVerificationEmailCommand.builder()
        .identifier(identifier)
        .ipAddress("203.0.113.5")
        .build();
  }

  private static User pendingUser(UUID userId) {
    return User.builder()
        .id(userId)
        .username("operator1")
        .normalizedUsername("operator1")
        .email("operator1@example.com")
        .normalizedEmail("operator1@example.com")
        .displayName("Operator One")
        .passwordHash("{bcrypt}hashed")
        .status(UserStatus.PENDING_VERIFY)
        .build();
  }

  private static User activeUser(UUID userId) {
    return User.builder()
        .id(userId)
        .username("operator2")
        .normalizedUsername("operator2")
        .email("operator2@example.com")
        .normalizedEmail("operator2@example.com")
        .displayName("Operator Two")
        .passwordHash("{bcrypt}hashed")
        .status(UserStatus.ACTIVE)
        .build();
  }
}
