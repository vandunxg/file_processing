package com.vandunxg.file_processing.auth.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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
import com.vandunxg.file_processing.auth.application.AuditTrail;
import com.vandunxg.file_processing.auth.application.capability.AuditLogEventPublisher;
import com.vandunxg.file_processing.auth.application.capability.AuthMetrics;
import com.vandunxg.file_processing.auth.application.capability.AuthThrottle;
import com.vandunxg.file_processing.auth.application.capability.CredentialVersionCache;
import com.vandunxg.file_processing.auth.application.capability.EmailSender;
import com.vandunxg.file_processing.auth.application.capability.PasswordHasher;
import com.vandunxg.file_processing.auth.application.capability.VerificationTokenGenerator;
import com.vandunxg.file_processing.auth.application.command.ChangePasswordCommand;
import com.vandunxg.file_processing.auth.application.command.ForgotPasswordCommand;
import com.vandunxg.file_processing.auth.application.command.ResetPasswordCommand;
import com.vandunxg.file_processing.auth.application.exception.AuthErrorCode;
import com.vandunxg.file_processing.auth.application.exception.AuthException;
import com.vandunxg.file_processing.auth.domain.PasswordResetTokenRepository;
import com.vandunxg.file_processing.auth.domain.SessionRepository;
import com.vandunxg.file_processing.auth.domain.UserRepository;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import com.vandunxg.file_processing.auth.domain.model.OperationType;
import com.vandunxg.file_processing.auth.domain.model.PasswordResetToken;
import com.vandunxg.file_processing.auth.domain.model.RevocationReason;
import com.vandunxg.file_processing.auth.domain.model.User;
import com.vandunxg.file_processing.auth.domain.model.UserStatus;
import com.vandunxg.file_processing.testsupport.AuthPropertiesFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class PasswordCommandServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-20T10:15:30Z");
  private static final String CURRENT_PASSWORD = "CurrentStrongPassw0rd!";
  private static final String NEW_PASSWORD = "NewStrongPassw0rd!";

  @Mock private AuthThrottle authThrottle;
  @Mock private UserRepository userRepository;
  @Mock private PasswordResetTokenRepository tokenRepository;
  @Mock private PasswordHasher passwordHasher;
  @Mock private VerificationTokenGenerator verificationTokenGenerator;
  @Mock private SessionRepository sessionRepository;
  @Mock private CredentialVersionCache credentialVersionCache;
  @Mock private EmailSender emailSender;
  @Mock private AuditLogEventPublisher auditLogEventPublisher;
  @Mock private AuthMetrics authMetrics;

  private PasswordCommandService passwordCommandService;

  @BeforeEach
  void setUp() {
    passwordCommandService =
        new PasswordCommandService(
            authThrottle,
            userRepository,
            tokenRepository,
            passwordHasher,
            verificationTokenGenerator,
            sessionRepository,
            credentialVersionCache,
            emailSender,
            new AuditTrail(auditLogEventPublisher),
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

  private static void runAfterCommit() {
    new ArrayList<>(TransactionSynchronizationManager.getSynchronizations())
        .forEach(TransactionSynchronization::afterCommit);
  }

  @Nested
  class Change {

    @Test
    void changeUpdatesPasswordRevokesSessionsAndPublishesAuditAfterCommit() {
      UUID userId = UUID.randomUUID();
      User user = user(userId);
      when(userRepository.findById(userId)).thenReturn(Optional.of(user));
      when(passwordHasher.matches(CURRENT_PASSWORD, "{bcrypt}current")).thenReturn(true);
      when(passwordHasher.matches(NEW_PASSWORD, "{bcrypt}current")).thenReturn(false);
      when(passwordHasher.hash(NEW_PASSWORD)).thenReturn("{bcrypt}new");
      TransactionSynchronizationManager.initSynchronization();

      passwordCommandService.change(command(userId, CURRENT_PASSWORD, NEW_PASSWORD, NEW_PASSWORD));

      assertThat(user.getPasswordHash()).isEqualTo("{bcrypt}new");
      assertThat(user.isMustChangePassword()).isFalse();
      assertThat(user.getCredentialVersion()).isEqualTo(2);
      verify(userRepository).save(user);
      verify(sessionRepository)
          .revokeAllForUser(eq(userId), eq(RevocationReason.PASSWORD_CHANGED), eq(NOW));
      verifyNoInteractions(credentialVersionCache, auditLogEventPublisher);

      runAfterCommit();

      verify(credentialVersionCache).invalidate(userId);
      ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
      verify(auditLogEventPublisher).publish(auditCaptor.capture());
      assertThat(auditCaptor.getValue().getOperation()).isEqualTo(OperationType.PASSWORD_CHANGED);
    }

    @Test
    void changeRejectsAnInvalidCurrentPassword() {
      UUID userId = UUID.randomUUID();
      when(userRepository.findById(userId)).thenReturn(Optional.of(user(userId)));
      when(passwordHasher.matches(CURRENT_PASSWORD, "{bcrypt}current")).thenReturn(false);

      assertThatThrownBy(
              () ->
                  passwordCommandService.change(
                      command(userId, CURRENT_PASSWORD, NEW_PASSWORD, NEW_PASSWORD)))
          .isInstanceOf(AuthException.class)
          .extracting("error")
          .isEqualTo(AuthErrorCode.AUTH_CURRENT_PASSWORD_INVALID);

      verify(passwordHasher, never()).hash(any());
      verifyNoInteractions(sessionRepository, credentialVersionCache, auditLogEventPublisher);
    }

    @Test
    void changeRejectsMismatchedConfirmation() {
      UUID userId = UUID.randomUUID();
      when(userRepository.findById(userId)).thenReturn(Optional.of(user(userId)));
      when(passwordHasher.matches(CURRENT_PASSWORD, "{bcrypt}current")).thenReturn(true);

      assertThatThrownBy(
              () ->
                  passwordCommandService.change(
                      command(userId, CURRENT_PASSWORD, NEW_PASSWORD, "different-password")))
          .isInstanceOf(AuthException.class)
          .extracting("error")
          .isEqualTo(AuthErrorCode.AUTH_PASSWORD_CONFIRMATION_MISMATCH);

      verify(passwordHasher, never()).hash(any());
      verifyNoInteractions(sessionRepository, credentialVersionCache, auditLogEventPublisher);
    }

    @Test
    void changeRejectsAPasswordThatViolatesPolicy() {
      UUID userId = UUID.randomUUID();
      when(userRepository.findById(userId)).thenReturn(Optional.of(user(userId)));
      when(passwordHasher.matches(CURRENT_PASSWORD, "{bcrypt}current")).thenReturn(true);

      assertThatThrownBy(
              () ->
                  passwordCommandService.change(
                      command(userId, CURRENT_PASSWORD, "short", "short")))
          .isInstanceOf(AuthException.class)
          .extracting("error")
          .isEqualTo(AuthErrorCode.AUTH_PASSWORD_POLICY_VIOLATION);

      verify(passwordHasher, never()).hash(any());
      verifyNoInteractions(sessionRepository, credentialVersionCache, auditLogEventPublisher);
    }

    @Test
    void changeRejectsTheCurrentPasswordAsTheNewPassword() {
      UUID userId = UUID.randomUUID();
      when(userRepository.findById(userId)).thenReturn(Optional.of(user(userId)));
      when(passwordHasher.matches(CURRENT_PASSWORD, "{bcrypt}current")).thenReturn(true);

      assertThatThrownBy(
              () ->
                  passwordCommandService.change(
                      command(userId, CURRENT_PASSWORD, CURRENT_PASSWORD, CURRENT_PASSWORD)))
          .isInstanceOf(AuthException.class)
          .extracting("error")
          .isEqualTo(AuthErrorCode.AUTH_PASSWORD_REUSE_NOT_ALLOWED);

      verify(passwordHasher, never()).hash(any());
      verifyNoInteractions(sessionRepository, credentialVersionCache, auditLogEventPublisher);
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

  @Nested
  class RequestReset {

    private static final String IDENTIFIER = "operator01@example.com";
    private static final String RAW_TOKEN = "new-password-reset-token";

    @Test
    void requestResetInvalidatesOldTokensPersistsSha256TokenAndEmailsOnlyAfterCommit() {
      UUID userId = UUID.randomUUID();
      User user = user(userId, UserStatus.ACTIVE);
      when(authThrottle.tryConsume(anyString(), anyInt(), any())).thenReturn(true);
      when(userRepository.findByNormalizedIdentifier(IDENTIFIER)).thenReturn(Optional.of(user));
      when(verificationTokenGenerator.generate()).thenReturn(RAW_TOKEN);
      when(tokenRepository.save(any(PasswordResetToken.class)))
          .thenAnswer(invocation -> invocation.getArgument(0));
      TransactionSynchronizationManager.initSynchronization();

      passwordCommandService.requestReset(
          ForgotPasswordCommand.builder()
              .identifier("  " + IDENTIFIER + "  ")
              .ipAddress("203.0.113.5")
              .build());

      verify(tokenRepository).invalidateAllForUser(userId, NOW);
      ArgumentCaptor<PasswordResetToken> tokenCaptor =
          ArgumentCaptor.forClass(PasswordResetToken.class);
      verify(tokenRepository).save(tokenCaptor.capture());
      assertThat(tokenCaptor.getValue().getTokenHash())
          .isEqualTo(HashUtils.sha256(RAW_TOKEN.getBytes(StandardCharsets.UTF_8)));
      assertThat(tokenCaptor.getValue().getExpiresAt()).isEqualTo(NOW.plusSeconds(900));
      verifyNoInteractions(emailSender, auditLogEventPublisher);

      runAfterCommit();

      verify(emailSender)
          .sendPasswordResetEmail(
              "operator01@example.com",
              "Operator One",
              "https://app.example.com/reset-password?token=" + RAW_TOKEN);
      ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
      verify(auditLogEventPublisher).publish(auditCaptor.capture());
      assertThat(auditCaptor.getValue().getOperation())
          .isEqualTo(OperationType.PASSWORD_RESET_REQUESTED);
      verify(authMetrics).passwordResetRequested();
    }

    @Test
    void requestResetReturnsUserNotFoundForUnknownIdentifierAfterBothRateLimitsAllow() {
      when(authThrottle.tryConsume(anyString(), anyInt(), any())).thenReturn(true);
      when(userRepository.findByNormalizedIdentifier(IDENTIFIER)).thenReturn(Optional.empty());

      assertThatThrownBy(
              () ->
                  passwordCommandService.requestReset(
                      ForgotPasswordCommand.builder()
                          .identifier(IDENTIFIER)
                          .ipAddress("203.0.113.5")
                          .build()))
          .isInstanceOf(AuthException.class)
          .extracting("error")
          .isEqualTo(AuthErrorCode.USER_NOT_FOUND);

      verifyNoInteractions(tokenRepository, verificationTokenGenerator, emailSender);
    }

    @Test
    void requestResetIsNoContentForDisabledUserAndInvalidatesOldTokensWithoutIssuingAnother() {
      UUID userId = UUID.randomUUID();
      when(authThrottle.tryConsume(anyString(), anyInt(), any())).thenReturn(true);
      when(userRepository.findByNormalizedIdentifier(IDENTIFIER))
          .thenReturn(Optional.of(user(userId, UserStatus.DISABLED)));

      passwordCommandService.requestReset(
          ForgotPasswordCommand.builder().identifier(IDENTIFIER).ipAddress("203.0.113.5").build());

      verify(tokenRepository).invalidateAllForUser(userId, NOW);
      verify(tokenRepository, never()).save(any());
      verifyNoInteractions(verificationTokenGenerator, emailSender, auditLogEventPublisher);
    }

    @Test
    void requestResetRejectsWhenTheNormalizedIdentifierRateLimitIsExceededBeforeLookup() {
      when(authThrottle.tryConsume(anyString(), anyInt(), any())).thenReturn(true, false);

      assertThatThrownBy(
              () ->
                  passwordCommandService.requestReset(
                      ForgotPasswordCommand.builder()
                          .identifier(IDENTIFIER)
                          .ipAddress("203.0.113.5")
                          .build()))
          .isInstanceOf(AuthException.class)
          .extracting("error")
          .isEqualTo(AuthErrorCode.AUTH_RATE_LIMITED);

      verifyNoInteractions(
          userRepository,
          tokenRepository,
          verificationTokenGenerator,
          emailSender,
          auditLogEventPublisher);
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

  @Nested
  class Reset {

    private static final String RAW_TOKEN = "reset-token";

    @Test
    void resetConsumesTokenChangesPasswordRevokesSessionsAndPublishesAuditAfterCommit() {
      UUID userId = UUID.randomUUID();
      User user = user(userId, UserStatus.ACTIVE);
      user.registerFailedLogin(NOW.minusSeconds(1), 1, Duration.ofMinutes(15));
      PasswordResetToken token = token(userId);
      when(tokenRepository.findByTokenHashForUpdate(tokenHash())).thenReturn(Optional.of(token));
      when(userRepository.findById(userId)).thenReturn(Optional.of(user));
      when(passwordHasher.matches(NEW_PASSWORD, "{bcrypt}current")).thenReturn(false);
      when(passwordHasher.hash(NEW_PASSWORD)).thenReturn("{bcrypt}new");
      when(tokenRepository.save(any(PasswordResetToken.class)))
          .thenAnswer(invocation -> invocation.getArgument(0));
      when(userRepository.save(any(User.class)))
          .thenAnswer(invocation -> invocation.getArgument(0));
      TransactionSynchronizationManager.initSynchronization();

      passwordCommandService.reset(command(NEW_PASSWORD, NEW_PASSWORD));

      assertThat(token.getUsedAt()).isEqualTo(NOW);
      assertThat(user.getPasswordHash()).isEqualTo("{bcrypt}new");
      assertThat(user.getCredentialVersion()).isEqualTo(2);
      assertThat(user.getFailedLoginCount()).isZero();
      assertThat(user.getLockedUntil()).isNull();
      verify(sessionRepository)
          .revokeAllForUser(eq(userId), eq(RevocationReason.PASSWORD_CHANGED), eq(NOW));
      // Deferred like change() does: invalidating inline would let a Redis outage roll the whole
      // reset back and strand the user on a one-shot email link.
      verifyNoInteractions(credentialVersionCache, auditLogEventPublisher);

      runAfterCommit();

      verify(credentialVersionCache).invalidate(userId);
      ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
      verify(auditLogEventPublisher).publish(auditCaptor.capture());
      assertThat(auditCaptor.getValue().getOperation())
          .isEqualTo(OperationType.PASSWORD_RESET_COMPLETED);
      verify(authMetrics).passwordResetCompleted();
    }

    @Test
    void resetActivatesAndVerifiesPendingUser() {
      UUID userId = UUID.randomUUID();
      User user = user(userId, UserStatus.PENDING_VERIFY);
      when(tokenRepository.findByTokenHashForUpdate(tokenHash()))
          .thenReturn(Optional.of(token(userId)));
      when(userRepository.findById(userId)).thenReturn(Optional.of(user));
      when(passwordHasher.matches(NEW_PASSWORD, "{bcrypt}current")).thenReturn(false);
      when(passwordHasher.hash(NEW_PASSWORD)).thenReturn("{bcrypt}new");
      when(tokenRepository.save(any(PasswordResetToken.class)))
          .thenAnswer(invocation -> invocation.getArgument(0));
      when(userRepository.save(any(User.class)))
          .thenAnswer(invocation -> invocation.getArgument(0));

      passwordCommandService.reset(command(NEW_PASSWORD, NEW_PASSWORD));

      assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
      assertThat(user.getEmailVerifiedAt()).isEqualTo(NOW);
    }

    @Test
    void resetRejectsMismatchedPasswordConfirmationWithoutConsumingTheToken() {
      UUID userId = UUID.randomUUID();
      PasswordResetToken token = token(userId);
      when(tokenRepository.findByTokenHashForUpdate(tokenHash())).thenReturn(Optional.of(token));
      when(userRepository.findById(userId))
          .thenReturn(Optional.of(user(userId, UserStatus.ACTIVE)));

      assertThatThrownBy(
              () -> passwordCommandService.reset(command(NEW_PASSWORD, "different-password")))
          .isInstanceOf(AuthException.class)
          .extracting("error")
          .isEqualTo(AuthErrorCode.AUTH_PASSWORD_CONFIRMATION_MISMATCH);

      assertThat(token.getUsedAt()).isNull();
      verify(tokenRepository, never()).save(any());
      verifyNoInteractions(passwordHasher, sessionRepository, credentialVersionCache);
      verify(authMetrics).passwordResetRejected();
    }

    @Test
    void resetRejectsTheCurrentPasswordWithoutConsumingTheToken() {
      UUID userId = UUID.randomUUID();
      PasswordResetToken token = token(userId);
      when(tokenRepository.findByTokenHashForUpdate(tokenHash())).thenReturn(Optional.of(token));
      when(userRepository.findById(userId))
          .thenReturn(Optional.of(user(userId, UserStatus.ACTIVE)));
      when(passwordHasher.matches(NEW_PASSWORD, "{bcrypt}current")).thenReturn(true);

      assertThatThrownBy(() -> passwordCommandService.reset(command(NEW_PASSWORD, NEW_PASSWORD)))
          .isInstanceOf(AuthException.class)
          .extracting("error")
          .isEqualTo(AuthErrorCode.AUTH_PASSWORD_SAME_AS_CURRENT);

      assertThat(token.getUsedAt()).isNull();
      verify(tokenRepository, never()).save(any());
      verify(passwordHasher, never()).hash(any());
      verifyNoInteractions(sessionRepository, credentialVersionCache);
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
          UUID.randomUUID(),
          userId,
          tokenHash(),
          NOW.minusSeconds(60),
          Duration.ofMinutes(15),
          null);
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
}
