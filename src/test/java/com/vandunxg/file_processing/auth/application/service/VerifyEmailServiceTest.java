package com.vandunxg.file_processing.auth.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
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

import com.vandunxg.common.utils.HashUtils;
import com.vandunxg.file_processing.auth.application.command.VerifyEmailCommand;
import com.vandunxg.file_processing.auth.application.port.out.AuditLogEventPublisherPort;
import com.vandunxg.file_processing.auth.application.port.out.EmailVerificationTokenRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.UserRepositoryPort;
import com.vandunxg.file_processing.auth.application.result.RegisterResult;
import com.vandunxg.file_processing.auth.domain.exception.AuthDomainException;
import com.vandunxg.file_processing.auth.domain.exception.AuthErrorCode;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import com.vandunxg.file_processing.auth.domain.model.EmailVerificationToken;
import com.vandunxg.file_processing.auth.domain.model.OperationType;
import com.vandunxg.file_processing.auth.domain.model.User;
import com.vandunxg.file_processing.auth.domain.model.UserStatus;
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
class VerifyEmailServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-17T10:15:30Z");
  private static final String RAW_TOKEN = "raw-verification-token";
  private static final String TOKEN_HASH =
      HashUtils.sha256(RAW_TOKEN.getBytes(StandardCharsets.UTF_8));

  @Mock private EmailVerificationTokenRepositoryPort tokenRepositoryPort;
  @Mock private UserRepositoryPort userRepositoryPort;
  @Mock private AuditLogEventPublisherPort auditLogEventPublisherPort;

  private VerifyEmailService verifyEmailService;

  @BeforeEach
  void setUp() {
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    verifyEmailService =
        new VerifyEmailService(
            tokenRepositoryPort, userRepositoryPort, auditLogEventPublisherPort, clock);
  }

  @AfterEach
  void tearDown() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void verifyEmailActivatesUserConsumesTokenAndPublishesAuditEventAfterCommitWhenTokenValid() {
    UUID userId = UUID.randomUUID();
    EmailVerificationToken token = pendingToken(userId);
    User user = pendingUser(userId);

    when(tokenRepositoryPort.findByTokenHashForUpdate(TOKEN_HASH)).thenReturn(Optional.of(token));
    when(tokenRepositoryPort.save(any(EmailVerificationToken.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(userRepositoryPort.findById(userId)).thenReturn(Optional.of(user));
    when(userRepositoryPort.save(any(User.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    TransactionSynchronizationManager.initSynchronization();

    RegisterResult result =
        verifyEmailService.verifyEmail(VerifyEmailCommand.builder().token(RAW_TOKEN).build());

    assertThat(result.getId()).isEqualTo(userId);
    assertThat(result.getStatus()).isEqualTo(UserStatus.ACTIVE);
    assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
    assertThat(token.getUsedAt()).isEqualTo(NOW);

    verifyNoInteractions(auditLogEventPublisherPort);

    new ArrayList<>(TransactionSynchronizationManager.getSynchronizations())
        .forEach(TransactionSynchronization::afterCommit);

    ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogEventPublisherPort).publish(auditCaptor.capture());
    assertThat(auditCaptor.getValue().getOperation()).isEqualTo(OperationType.EMAIL_VERIFIED);
    assertThat(auditCaptor.getValue().getObjectId()).isEqualTo(userId);
    assertThat(auditCaptor.getValue().getChangedBy()).isEqualTo(userId);

    // second call with the same raw token must fail: the mock returns the same
    // (now-consumed) token instance, so EmailVerificationToken#consume rejects it.
    assertThatThrownBy(
            () ->
                verifyEmailService.verifyEmail(
                    VerifyEmailCommand.builder().token(RAW_TOKEN).build()))
        .isInstanceOf(AuthDomainException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.EMAIL_VERIFICATION_TOKEN_INVALID);
  }

  @Test
  void verifyEmailThrowsInvalidTokenWhenTokenHashUnknown() {
    when(tokenRepositoryPort.findByTokenHashForUpdate(TOKEN_HASH)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                verifyEmailService.verifyEmail(
                    VerifyEmailCommand.builder().token(RAW_TOKEN).build()))
        .isInstanceOf(AuthDomainException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.EMAIL_VERIFICATION_TOKEN_INVALID);

    verify(tokenRepositoryPort, never()).save(any());
    verifyNoInteractions(userRepositoryPort, auditLogEventPublisherPort);
  }

  @Test
  void verifyEmailThrowsInvalidTokenWhenTokenExpired() {
    EmailVerificationToken token = expiredToken(UUID.randomUUID());
    when(tokenRepositoryPort.findByTokenHashForUpdate(TOKEN_HASH)).thenReturn(Optional.of(token));

    assertThatThrownBy(
            () ->
                verifyEmailService.verifyEmail(
                    VerifyEmailCommand.builder().token(RAW_TOKEN).build()))
        .isInstanceOf(AuthDomainException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.EMAIL_VERIFICATION_TOKEN_INVALID);

    verify(tokenRepositoryPort, never()).save(any());
    verifyNoInteractions(userRepositoryPort, auditLogEventPublisherPort);
  }

  @Test
  void verifyEmailThrowsInvalidTokenWhenTokenAlreadyUsed() {
    EmailVerificationToken token = usedToken(UUID.randomUUID());
    when(tokenRepositoryPort.findByTokenHashForUpdate(TOKEN_HASH)).thenReturn(Optional.of(token));

    assertThatThrownBy(
            () ->
                verifyEmailService.verifyEmail(
                    VerifyEmailCommand.builder().token(RAW_TOKEN).build()))
        .isInstanceOf(AuthDomainException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.EMAIL_VERIFICATION_TOKEN_INVALID);

    verify(tokenRepositoryPort, never()).save(any());
    verifyNoInteractions(userRepositoryPort, auditLogEventPublisherPort);
  }

  private static EmailVerificationToken pendingToken(UUID userId) {
    return EmailVerificationToken.issue(
        UUID.randomUUID(), userId, TOKEN_HASH, NOW.minusSeconds(60), Duration.ofMinutes(15), null);
  }

  private static EmailVerificationToken expiredToken(UUID userId) {
    return EmailVerificationToken.issue(
        UUID.randomUUID(),
        userId,
        TOKEN_HASH,
        NOW.minus(Duration.ofMinutes(20)),
        Duration.ofMinutes(15),
        null);
  }

  private static EmailVerificationToken usedToken(UUID userId) {
    EmailVerificationToken token = pendingToken(userId);
    token.consume(NOW.minusSeconds(30));
    return token;
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
}
