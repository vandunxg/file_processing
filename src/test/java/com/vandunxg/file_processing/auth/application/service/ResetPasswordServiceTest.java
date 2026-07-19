package com.vandunxg.file_processing.auth.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import com.vandunxg.file_processing.auth.application.command.ResetPasswordCommand;
import com.vandunxg.file_processing.auth.application.port.out.AuditLogEventPublisherPort;
import com.vandunxg.file_processing.auth.application.port.out.CredentialVersionCachePort;
import com.vandunxg.file_processing.auth.application.port.out.PasswordHasherPort;
import com.vandunxg.file_processing.auth.application.port.out.PasswordResetTokenRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.SessionRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.UserRepositoryPort;
import com.vandunxg.file_processing.auth.domain.exception.AuthDomainException;
import com.vandunxg.file_processing.auth.domain.exception.AuthErrorCode;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import com.vandunxg.file_processing.auth.domain.model.OperationType;
import com.vandunxg.file_processing.auth.domain.model.PasswordResetToken;
import com.vandunxg.file_processing.auth.domain.model.RevocationReason;
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
class ResetPasswordServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-20T10:15:30Z");
  private static final String RAW_TOKEN = "reset-token";
  private static final String NEW_PASSWORD = "NewStrongPassw0rd!";

  @Mock private PasswordResetTokenRepositoryPort tokenRepositoryPort;
  @Mock private UserRepositoryPort userRepositoryPort;
  @Mock private PasswordHasherPort passwordHasherPort;
  @Mock private SessionRepositoryPort sessionRepositoryPort;
  @Mock private CredentialVersionCachePort credentialVersionCachePort;
  @Mock private AuditLogEventPublisherPort auditLogEventPublisherPort;

  private ResetPasswordService resetPasswordService;

  @BeforeEach
  void setUp() {
    resetPasswordService =
        new ResetPasswordService(
            tokenRepositoryPort,
            userRepositoryPort,
            passwordHasherPort,
            sessionRepositoryPort,
            credentialVersionCachePort,
            auditLogEventPublisherPort,
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @AfterEach
  void tearDown() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void resetConsumesTokenChangesPasswordRevokesSessionsAndPublishesAuditAfterCommit() {
    UUID userId = UUID.randomUUID();
    User user = user(userId, UserStatus.ACTIVE);
    user.registerFailedLogin(NOW.minusSeconds(1), 1, Duration.ofMinutes(15));
    PasswordResetToken token = token(userId);
    when(tokenRepositoryPort.findByTokenHashForUpdate(tokenHash())).thenReturn(Optional.of(token));
    when(userRepositoryPort.findById(userId)).thenReturn(Optional.of(user));
    when(passwordHasherPort.matches(NEW_PASSWORD, "{bcrypt}current")).thenReturn(false);
    when(passwordHasherPort.hash(NEW_PASSWORD)).thenReturn("{bcrypt}new");
    when(tokenRepositoryPort.save(any(PasswordResetToken.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(userRepositoryPort.save(any(User.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    TransactionSynchronizationManager.initSynchronization();

    resetPasswordService.reset(command(NEW_PASSWORD, NEW_PASSWORD));

    assertThat(token.getUsedAt()).isEqualTo(NOW);
    assertThat(user.getPasswordHash()).isEqualTo("{bcrypt}new");
    assertThat(user.getCredentialVersion()).isEqualTo(2);
    assertThat(user.getFailedLoginCount()).isZero();
    assertThat(user.getLockedUntil()).isNull();
    verify(sessionRepositoryPort)
        .revokeAllForUser(eq(userId), eq(RevocationReason.PASSWORD_CHANGED), eq(NOW));
    verify(credentialVersionCachePort).invalidate(userId);
    verifyNoInteractions(auditLogEventPublisherPort);

    new ArrayList<>(TransactionSynchronizationManager.getSynchronizations())
        .forEach(TransactionSynchronization::afterCommit);

    ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogEventPublisherPort).publish(auditCaptor.capture());
    assertThat(auditCaptor.getValue().getOperation())
        .isEqualTo(OperationType.PASSWORD_RESET_COMPLETED);
  }

  @Test
  void resetActivatesAndVerifiesPendingUser() {
    UUID userId = UUID.randomUUID();
    User user = user(userId, UserStatus.PENDING_VERIFY);
    when(tokenRepositoryPort.findByTokenHashForUpdate(tokenHash()))
        .thenReturn(Optional.of(token(userId)));
    when(userRepositoryPort.findById(userId)).thenReturn(Optional.of(user));
    when(passwordHasherPort.matches(NEW_PASSWORD, "{bcrypt}current")).thenReturn(false);
    when(passwordHasherPort.hash(NEW_PASSWORD)).thenReturn("{bcrypt}new");
    when(tokenRepositoryPort.save(any(PasswordResetToken.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(userRepositoryPort.save(any(User.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    resetPasswordService.reset(command(NEW_PASSWORD, NEW_PASSWORD));

    assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
    assertThat(user.getEmailVerifiedAt()).isEqualTo(NOW);
  }

  @Test
  void resetRejectsMismatchedPasswordConfirmationWithoutConsumingTheToken() {
    UUID userId = UUID.randomUUID();
    PasswordResetToken token = token(userId);
    when(tokenRepositoryPort.findByTokenHashForUpdate(tokenHash())).thenReturn(Optional.of(token));
    when(userRepositoryPort.findById(userId))
        .thenReturn(Optional.of(user(userId, UserStatus.ACTIVE)));

    assertThatThrownBy(
            () -> resetPasswordService.reset(command(NEW_PASSWORD, "different-password")))
        .isInstanceOf(AuthDomainException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.PASSWORD_CONFIRMATION_MISMATCH);

    assertThat(token.getUsedAt()).isNull();
    verify(tokenRepositoryPort, never()).save(any());
    verifyNoInteractions(passwordHasherPort, sessionRepositoryPort, credentialVersionCachePort);
  }

  @Test
  void resetRejectsTheCurrentPasswordWithoutConsumingTheToken() {
    UUID userId = UUID.randomUUID();
    PasswordResetToken token = token(userId);
    when(tokenRepositoryPort.findByTokenHashForUpdate(tokenHash())).thenReturn(Optional.of(token));
    when(userRepositoryPort.findById(userId))
        .thenReturn(Optional.of(user(userId, UserStatus.ACTIVE)));
    when(passwordHasherPort.matches(NEW_PASSWORD, "{bcrypt}current")).thenReturn(true);

    assertThatThrownBy(() -> resetPasswordService.reset(command(NEW_PASSWORD, NEW_PASSWORD)))
        .isInstanceOf(AuthDomainException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.PASSWORD_SAME_AS_CURRENT);

    assertThat(token.getUsedAt()).isNull();
    verify(tokenRepositoryPort, never()).save(any());
    verify(passwordHasherPort, never()).hash(any());
    verifyNoInteractions(sessionRepositoryPort, credentialVersionCachePort);
  }

  private static ResetPasswordCommand command(String password, String confirmPassword) {
    return ResetPasswordCommand.builder()
        .token(RAW_TOKEN)
        .newPassword(password)
        .confirmPassword(confirmPassword)
        .ipAddress("203.0.113.5")
        .build();
  }

  private static String tokenHash() {
    return HashUtils.sha256(RAW_TOKEN.getBytes(StandardCharsets.UTF_8));
  }

  private static PasswordResetToken token(UUID userId) {
    return PasswordResetToken.issue(
        UUID.randomUUID(), userId, tokenHash(), NOW.minusSeconds(60), Duration.ofMinutes(15), null);
  }

  private static User user(UUID id, UserStatus status) {
    return User.builder()
        .id(id)
        .username("operator01")
        .normalizedUsername("operator01")
        .email("operator01@example.com")
        .normalizedEmail("operator01@example.com")
        .displayName("Operator One")
        .passwordHash("{bcrypt}current")
        .status(status)
        .credentialVersion(1)
        .build();
  }
}
