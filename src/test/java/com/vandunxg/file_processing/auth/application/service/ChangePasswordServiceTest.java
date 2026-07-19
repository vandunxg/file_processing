package com.vandunxg.file_processing.auth.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

import com.vandunxg.file_processing.auth.application.command.ChangePasswordCommand;
import com.vandunxg.file_processing.auth.application.port.out.AuditLogEventPublisherPort;
import com.vandunxg.file_processing.auth.application.port.out.CredentialVersionCachePort;
import com.vandunxg.file_processing.auth.application.port.out.PasswordHasherPort;
import com.vandunxg.file_processing.auth.application.port.out.SessionRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.UserRepositoryPort;
import com.vandunxg.file_processing.auth.domain.exception.AuthDomainException;
import com.vandunxg.file_processing.auth.domain.exception.AuthErrorCode;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import com.vandunxg.file_processing.auth.domain.model.OperationType;
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
class ChangePasswordServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-20T10:15:30Z");
  private static final String CURRENT_PASSWORD = "CurrentStrongPassw0rd!";
  private static final String NEW_PASSWORD = "NewStrongPassw0rd!";

  @Mock private UserRepositoryPort userRepositoryPort;
  @Mock private PasswordHasherPort passwordHasherPort;
  @Mock private SessionRepositoryPort sessionRepositoryPort;
  @Mock private CredentialVersionCachePort credentialVersionCachePort;
  @Mock private AuditLogEventPublisherPort auditLogEventPublisherPort;

  private ChangePasswordService changePasswordService;

  @BeforeEach
  void setUp() {
    changePasswordService =
        new ChangePasswordService(
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
  void changeUpdatesPasswordRevokesSessionsAndPublishesAuditAfterCommit() {
    UUID userId = UUID.randomUUID();
    User user = user(userId);
    when(userRepositoryPort.findById(userId)).thenReturn(Optional.of(user));
    when(passwordHasherPort.matches(CURRENT_PASSWORD, "{bcrypt}current")).thenReturn(true);
    when(passwordHasherPort.matches(NEW_PASSWORD, "{bcrypt}current")).thenReturn(false);
    when(passwordHasherPort.hash(NEW_PASSWORD)).thenReturn("{bcrypt}new");
    TransactionSynchronizationManager.initSynchronization();

    changePasswordService.change(command(userId, CURRENT_PASSWORD, NEW_PASSWORD, NEW_PASSWORD));

    assertThat(user.getPasswordHash()).isEqualTo("{bcrypt}new");
    assertThat(user.isMustChangePassword()).isFalse();
    assertThat(user.getCredentialVersion()).isEqualTo(2);
    verify(userRepositoryPort).save(user);
    verify(sessionRepositoryPort)
        .revokeAllForUser(eq(userId), eq(RevocationReason.PASSWORD_CHANGED), eq(NOW));
    verifyNoInteractions(credentialVersionCachePort, auditLogEventPublisherPort);

    new ArrayList<>(TransactionSynchronizationManager.getSynchronizations())
        .forEach(TransactionSynchronization::afterCommit);

    verify(credentialVersionCachePort).invalidate(userId);
    ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogEventPublisherPort).publish(auditCaptor.capture());
    assertThat(auditCaptor.getValue().getOperation()).isEqualTo(OperationType.PASSWORD_CHANGED);
  }

  @Test
  void changeRejectsAnInvalidCurrentPassword() {
    UUID userId = UUID.randomUUID();
    when(userRepositoryPort.findById(userId)).thenReturn(Optional.of(user(userId)));
    when(passwordHasherPort.matches(CURRENT_PASSWORD, "{bcrypt}current")).thenReturn(false);

    assertThatThrownBy(
            () ->
                changePasswordService.change(
                    command(userId, CURRENT_PASSWORD, NEW_PASSWORD, NEW_PASSWORD)))
        .isInstanceOf(AuthDomainException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.CURRENT_PASSWORD_INVALID);

    verify(passwordHasherPort, never()).hash(any());
    verifyNoInteractions(
        sessionRepositoryPort, credentialVersionCachePort, auditLogEventPublisherPort);
  }

  @Test
  void changeRejectsMismatchedConfirmation() {
    UUID userId = UUID.randomUUID();
    when(userRepositoryPort.findById(userId)).thenReturn(Optional.of(user(userId)));
    when(passwordHasherPort.matches(CURRENT_PASSWORD, "{bcrypt}current")).thenReturn(true);

    assertThatThrownBy(
            () ->
                changePasswordService.change(
                    command(userId, CURRENT_PASSWORD, NEW_PASSWORD, "different-password")))
        .isInstanceOf(AuthDomainException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.PASSWORD_CONFIRMATION_MISMATCH);

    verify(passwordHasherPort, never()).hash(any());
    verifyNoInteractions(
        sessionRepositoryPort, credentialVersionCachePort, auditLogEventPublisherPort);
  }

  @Test
  void changeRejectsAPasswordThatViolatesPolicy() {
    UUID userId = UUID.randomUUID();
    when(userRepositoryPort.findById(userId)).thenReturn(Optional.of(user(userId)));
    when(passwordHasherPort.matches(CURRENT_PASSWORD, "{bcrypt}current")).thenReturn(true);

    assertThatThrownBy(
            () -> changePasswordService.change(command(userId, CURRENT_PASSWORD, "short", "short")))
        .isInstanceOf(AuthDomainException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.PASSWORD_POLICY_VIOLATION);

    verify(passwordHasherPort, never()).hash(any());
    verifyNoInteractions(
        sessionRepositoryPort, credentialVersionCachePort, auditLogEventPublisherPort);
  }

  @Test
  void changeRejectsTheCurrentPasswordAsTheNewPassword() {
    UUID userId = UUID.randomUUID();
    when(userRepositoryPort.findById(userId)).thenReturn(Optional.of(user(userId)));
    when(passwordHasherPort.matches(CURRENT_PASSWORD, "{bcrypt}current")).thenReturn(true);

    assertThatThrownBy(
            () ->
                changePasswordService.change(
                    command(userId, CURRENT_PASSWORD, CURRENT_PASSWORD, CURRENT_PASSWORD)))
        .isInstanceOf(AuthDomainException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.PASSWORD_REUSE_NOT_ALLOWED);

    verify(passwordHasherPort, never()).hash(any());
    verifyNoInteractions(
        sessionRepositoryPort, credentialVersionCachePort, auditLogEventPublisherPort);
  }

  private static ChangePasswordCommand command(
      UUID userId, String currentPassword, String newPassword, String confirmPassword) {
    return ChangePasswordCommand.builder()
        .userId(userId)
        .currentPassword(currentPassword)
        .newPassword(newPassword)
        .confirmPassword(confirmPassword)
        .ipAddress("203.0.113.5")
        .build();
  }

  private static User user(UUID id) {
    return User.builder()
        .id(id)
        .username("operator01")
        .normalizedUsername("operator01")
        .email("operator01@example.com")
        .normalizedEmail("operator01@example.com")
        .displayName("Operator One")
        .passwordHash("{bcrypt}current")
        .status(UserStatus.ACTIVE)
        .mustChangePassword(true)
        .credentialVersion(1)
        .build();
  }
}
