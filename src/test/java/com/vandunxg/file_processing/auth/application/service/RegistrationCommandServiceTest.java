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
import com.vandunxg.file_processing.auth.application.capability.AuthThrottle;
import com.vandunxg.file_processing.auth.application.capability.PasswordHasher;
import com.vandunxg.file_processing.auth.application.capability.VerificationEmailEventPublisher;
import com.vandunxg.file_processing.auth.application.capability.VerificationTokenGenerator;
import com.vandunxg.file_processing.auth.application.command.RegisterCommand;
import com.vandunxg.file_processing.auth.application.command.ResendVerificationEmailCommand;
import com.vandunxg.file_processing.auth.application.command.VerifyEmailCommand;
import com.vandunxg.file_processing.auth.application.exception.AuthErrorCode;
import com.vandunxg.file_processing.auth.application.exception.AuthException;
import com.vandunxg.file_processing.auth.application.mapper.UserResultMapperImpl;
import com.vandunxg.file_processing.auth.application.result.RegisterResult;
import com.vandunxg.file_processing.auth.domain.EmailVerificationTokenRepository;
import com.vandunxg.file_processing.auth.domain.RoleRepository;
import com.vandunxg.file_processing.auth.domain.UserRepository;
import com.vandunxg.file_processing.auth.domain.model.ActiveStatus;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import com.vandunxg.file_processing.auth.domain.model.EmailVerificationToken;
import com.vandunxg.file_processing.auth.domain.model.OperationType;
import com.vandunxg.file_processing.auth.domain.model.Role;
import com.vandunxg.file_processing.auth.domain.model.User;
import com.vandunxg.file_processing.auth.domain.model.UserRole;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class RegistrationCommandServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-17T10:15:30Z");

  @Mock private AuthThrottle authThrottle;
  @Mock private UserRepository userRepository;
  @Mock private RoleRepository roleRepository;
  @Mock private EmailVerificationTokenRepository tokenRepository;
  @Mock private PasswordHasher passwordHasher;
  @Mock private VerificationTokenGenerator verificationTokenGenerator;
  @Mock private VerificationEmailEventPublisher verificationEmailEventPublisher;
  @Mock private AuditLogEventPublisher auditLogEventPublisher;

  private RegistrationCommandService registrationCommandService;

  @BeforeEach
  void setUp() {
    registrationCommandService =
        new RegistrationCommandService(
            authThrottle,
            userRepository,
            roleRepository,
            tokenRepository,
            passwordHasher,
            verificationTokenGenerator,
            verificationEmailEventPublisher,
            new UserResultMapperImpl(),
            new AuditTrail(auditLogEventPublisher),
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
  class Register {

    @Test
    void registerReturnsPendingVerifyResultAndPublishesEventsAfterCommitWhenValid() {
      Role operatorRole = operatorRole();
      RegisterCommand command = validCommand();
      givenThrottleAllows();
      givenNoExistingUsernameOrEmail();
      when(roleRepository.findByCode("OPERATOR")).thenReturn(Optional.of(operatorRole));
      when(passwordHasher.hash(command.password())).thenReturn("{bcrypt}hashed");
      when(userRepository.save(any(User.class)))
          .thenAnswer(invocation -> invocation.getArgument(0));
      when(userRepository.assignRole(any(UserRole.class)))
          .thenAnswer(invocation -> invocation.getArgument(0));
      when(verificationTokenGenerator.generate()).thenReturn("raw-verification-token");
      when(tokenRepository.save(any(EmailVerificationToken.class)))
          .thenAnswer(invocation -> invocation.getArgument(0));

      TransactionSynchronizationManager.initSynchronization();

      RegisterResult result = registrationCommandService.register(command);

      assertThat(result.status()).isEqualTo(UserStatus.PENDING_VERIFY);
      assertThat(result.username()).isEqualTo("operator1");
      assertThat(result.email()).isEqualTo("operator1@example.com");
      assertThat(result.displayName()).isEqualTo("Operator One");
      assertThat(result.id()).isNotNull();

      ArgumentCaptor<UserRole> userRoleCaptor = ArgumentCaptor.forClass(UserRole.class);
      verify(userRepository).assignRole(userRoleCaptor.capture());
      assertThat(userRoleCaptor.getValue().getUserId()).isEqualTo(result.id());
      assertThat(userRoleCaptor.getValue().getRoleId()).isEqualTo(operatorRole.getId());

      ArgumentCaptor<EmailVerificationToken> tokenCaptor =
          ArgumentCaptor.forClass(EmailVerificationToken.class);
      verify(tokenRepository).save(tokenCaptor.capture());
      String expectedHash =
          HashUtils.sha256("raw-verification-token".getBytes(StandardCharsets.UTF_8));
      assertThat(tokenCaptor.getValue().getTokenHash()).isEqualTo(expectedHash);
      assertThat(tokenCaptor.getValue().getTokenHash()).isNotEqualTo("raw-verification-token");
      assertThat(tokenCaptor.getValue().getUserId()).isEqualTo(result.id());

      verifyNoInteractions(auditLogEventPublisher, verificationEmailEventPublisher);

      runAfterCommit();

      ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
      verify(auditLogEventPublisher).publish(auditCaptor.capture());
      assertThat(auditCaptor.getValue().getOperation()).isEqualTo(OperationType.USER_REGISTERED);
      assertThat(auditCaptor.getValue().getObjectId()).isEqualTo(result.id());
      assertThat(auditCaptor.getValue().getChangedBy()).isEqualTo(result.id());

      verify(verificationEmailEventPublisher)
          .publish(
              "operator1@example.com",
              "Operator One",
              "https://app.example.com/verify?token=raw-verification-token");
    }

    @Test
    void registerThrowsUsernameAlreadyExistsWhenUsernameAlreadyTaken() {
      RegisterCommand command = validCommand();
      givenThrottleAllows();
      when(userRepository.existsByNormalizedUsername("operator1")).thenReturn(true);

      assertThatThrownBy(() -> registrationCommandService.register(command))
          .isInstanceOf(AuthException.class)
          .extracting("error")
          .isEqualTo(AuthErrorCode.AUTH_USERNAME_ALREADY_EXISTS);

      verify(passwordHasher, never()).hash(anyString());
      verify(verificationTokenGenerator, never()).generate();
      verify(userRepository, never()).save(any());
    }

    @Test
    void registerThrowsEmailAlreadyExistsWhenEmailAlreadyTaken() {
      RegisterCommand command = validCommand();
      givenThrottleAllows();
      when(userRepository.existsByNormalizedUsername("operator1")).thenReturn(false);
      when(userRepository.existsByNormalizedEmail("operator1@example.com")).thenReturn(true);

      assertThatThrownBy(() -> registrationCommandService.register(command))
          .isInstanceOf(AuthException.class)
          .extracting("error")
          .isEqualTo(AuthErrorCode.AUTH_EMAIL_ALREADY_EXISTS);

      verify(passwordHasher, never()).hash(anyString());
      verify(verificationTokenGenerator, never()).generate();
      verify(userRepository, never()).save(any());
    }

    @Test
    void registerTranslatesConcurrentSaveFailureToUsernameTakenWhenUsernameConstraintFires() {
      RegisterCommand command = validCommand();
      givenThrottleAllows();
      givenNoExistingUsernameOrEmail();
      when(roleRepository.findByCode("OPERATOR")).thenReturn(Optional.of(operatorRole()));
      when(passwordHasher.hash(command.password())).thenReturn("{bcrypt}hashed");
      when(userRepository.save(any(User.class)))
          .thenThrow(
              new DataIntegrityViolationException(
                  "duplicate key value violates unique constraint"
                      + " \"auth_users_normalized_username_uk\""));

      assertThatThrownBy(() -> registrationCommandService.register(command))
          .isInstanceOf(AuthException.class)
          .extracting("error")
          .isEqualTo(AuthErrorCode.AUTH_USERNAME_ALREADY_EXISTS);

      verifyNoInteractions(
          tokenRepository, auditLogEventPublisher, verificationEmailEventPublisher);
    }

    @Test
    void registerTranslatesConcurrentSaveFailureToEmailTakenWhenEmailConstraintFires() {
      RegisterCommand command = validCommand();
      givenThrottleAllows();
      givenNoExistingUsernameOrEmail();
      when(roleRepository.findByCode("OPERATOR")).thenReturn(Optional.of(operatorRole()));
      when(passwordHasher.hash(command.password())).thenReturn("{bcrypt}hashed");
      when(userRepository.save(any(User.class)))
          .thenThrow(
              new DataIntegrityViolationException(
                  "duplicate key value violates unique constraint"
                      + " \"auth_users_normalized_email_uk\""));

      assertThatThrownBy(() -> registrationCommandService.register(command))
          .isInstanceOf(AuthException.class)
          .extracting("error")
          .isEqualTo(AuthErrorCode.AUTH_EMAIL_ALREADY_EXISTS);

      verifyNoInteractions(
          tokenRepository, auditLogEventPublisher, verificationEmailEventPublisher);
    }

    /**
     * A failure that is not one of the two uniqueness constraints must surface as itself. Reporting
     * an infrastructure error as "username already exists" sends the caller off to pick a different
     * username for an account name that was never taken.
     */
    @Test
    void registerRethrowsAnIntegrityFailureThatIsNotADuplicate() {
      RegisterCommand command = validCommand();
      givenThrottleAllows();
      givenNoExistingUsernameOrEmail();
      when(roleRepository.findByCode("OPERATOR")).thenReturn(Optional.of(operatorRole()));
      when(passwordHasher.hash(command.password())).thenReturn("{bcrypt}hashed");
      when(userRepository.save(any(User.class)))
          .thenThrow(new DataIntegrityViolationException("connection reset by peer"));

      assertThatThrownBy(() -> registrationCommandService.register(command))
          .isInstanceOf(DataIntegrityViolationException.class)
          .isNotInstanceOf(AuthException.class);
    }

    @Test
    void registerReportsADuplicateUsernameFromItsConstraintName() {
      RegisterCommand command = validCommand();
      givenThrottleAllows();
      givenNoExistingUsernameOrEmail();
      when(roleRepository.findByCode("OPERATOR")).thenReturn(Optional.of(operatorRole()));
      when(passwordHasher.hash(command.password())).thenReturn("{bcrypt}hashed");
      when(userRepository.save(any(User.class)))
          .thenThrow(
              new DataIntegrityViolationException(
                  "duplicate key value violates unique constraint"
                      + " \"auth_users_normalized_username_uk\""));

      assertThatThrownBy(() -> registrationCommandService.register(command))
          .isInstanceOf(AuthException.class)
          .extracting("error")
          .isEqualTo(AuthErrorCode.AUTH_USERNAME_ALREADY_EXISTS);
    }

    @Test
    void registerThrowsPasswordPolicyViolationWhenPasswordTooShortAndNeverHashes() {
      RegisterCommand command =
          RegisterCommand.builder()
              .username("operator1")
              .email("operator1@example.com")
              .displayName("Operator One")
              .password("short1")
              .ipAddress("203.0.113.5")
              .build();
      givenThrottleAllows();

      assertThatThrownBy(() -> registrationCommandService.register(command))
          .isInstanceOf(AuthException.class)
          .extracting("error")
          .isEqualTo(AuthErrorCode.AUTH_PASSWORD_POLICY_VIOLATION);

      verify(passwordHasher, never()).hash(anyString());
      verify(verificationTokenGenerator, never()).generate();
      verifyNoInteractions(
          userRepository,
          roleRepository,
          tokenRepository,
          auditLogEventPublisher,
          verificationEmailEventPublisher);
    }

    @Test
    void registerThrowsRateLimitedBeforeAnyOtherCheckWhenThrottleExceeded() {
      RegisterCommand command = validCommand();
      when(authThrottle.tryConsume(
              eq("register:" + command.ipAddress()), eq(5), any(Duration.class)))
          .thenReturn(false);

      assertThatThrownBy(() -> registrationCommandService.register(command))
          .isInstanceOf(AuthException.class)
          .extracting("error")
          .isEqualTo(AuthErrorCode.AUTH_RATE_LIMITED);

      verifyNoInteractions(
          roleRepository,
          userRepository,
          passwordHasher,
          verificationTokenGenerator,
          tokenRepository,
          auditLogEventPublisher,
          verificationEmailEventPublisher);
    }

    @Test
    void registerThrowsInvalidRoleWhenOperatorRoleNotFound() {
      RegisterCommand command = validCommand();
      givenThrottleAllows();
      givenNoExistingUsernameOrEmail();
      when(roleRepository.findByCode("OPERATOR")).thenReturn(Optional.empty());

      assertThatThrownBy(() -> registrationCommandService.register(command))
          .isInstanceOf(AuthException.class)
          .extracting("error")
          .isEqualTo(AuthErrorCode.ROLE_INVALID);

      verify(passwordHasher, never()).hash(anyString());
      verify(verificationTokenGenerator, never()).generate();
    }

    private void givenThrottleAllows() {
      when(authThrottle.tryConsume(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
    }

    private void givenNoExistingUsernameOrEmail() {
      when(userRepository.existsByNormalizedUsername(anyString())).thenReturn(false);
      when(userRepository.existsByNormalizedEmail(anyString())).thenReturn(false);
    }

    private static RegisterCommand validCommand() {
      return RegisterCommand.builder()
          .username("operator1")
          .email("operator1@example.com")
          .displayName("Operator One")
          .password("StrongPassw0rd!")
          .ipAddress("203.0.113.5")
          .build();
    }

    private static Role operatorRole() {
      return Role.builder()
          .id(UUID.randomUUID())
          .code("OPERATOR")
          .status(ActiveStatus.ACTIVE)
          .build();
    }
  }

  @Nested
  class VerifyEmail {

    private static final String RAW_TOKEN = "raw-verification-token";
    private static final String TOKEN_HASH =
        HashUtils.sha256(RAW_TOKEN.getBytes(StandardCharsets.UTF_8));

    @Test
    void verifyEmailActivatesUserConsumesTokenAndPublishesAuditAfterCommitWhenTokenValid() {
      UUID userId = UUID.randomUUID();
      EmailVerificationToken token = pendingToken(userId);
      User user = pendingUser(userId);

      when(tokenRepository.findByTokenHashForUpdate(TOKEN_HASH)).thenReturn(Optional.of(token));
      when(tokenRepository.save(any(EmailVerificationToken.class)))
          .thenAnswer(invocation -> invocation.getArgument(0));
      when(userRepository.findById(userId)).thenReturn(Optional.of(user));
      when(userRepository.save(any(User.class)))
          .thenAnswer(invocation -> invocation.getArgument(0));

      TransactionSynchronizationManager.initSynchronization();

      RegisterResult result =
          registrationCommandService.verifyEmail(new VerifyEmailCommand(RAW_TOKEN));

      assertThat(result.id()).isEqualTo(userId);
      assertThat(result.status()).isEqualTo(UserStatus.ACTIVE);
      assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
      assertThat(token.getUsedAt()).isEqualTo(NOW);

      verifyNoInteractions(auditLogEventPublisher);

      runAfterCommit();

      ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
      verify(auditLogEventPublisher).publish(auditCaptor.capture());
      assertThat(auditCaptor.getValue().getOperation()).isEqualTo(OperationType.EMAIL_VERIFIED);
      assertThat(auditCaptor.getValue().getObjectId()).isEqualTo(userId);
      assertThat(auditCaptor.getValue().getChangedBy()).isEqualTo(userId);

      // second call with the same raw token must fail: the mock returns the same
      // (now-consumed) token instance, so EmailVerificationToken#consume rejects it.
      assertThatThrownBy(
              () -> registrationCommandService.verifyEmail(new VerifyEmailCommand(RAW_TOKEN)))
          .isInstanceOf(AuthException.class)
          .extracting("error")
          .isEqualTo(AuthErrorCode.AUTH_EMAIL_VERIFICATION_TOKEN_INVALID);
    }

    @Test
    void verifyEmailThrowsInvalidTokenWhenTokenHashUnknown() {
      when(tokenRepository.findByTokenHashForUpdate(TOKEN_HASH)).thenReturn(Optional.empty());

      assertThatThrownBy(
              () -> registrationCommandService.verifyEmail(new VerifyEmailCommand(RAW_TOKEN)))
          .isInstanceOf(AuthException.class)
          .extracting("error")
          .isEqualTo(AuthErrorCode.AUTH_EMAIL_VERIFICATION_TOKEN_INVALID);

      verify(tokenRepository, never()).save(any());
      verifyNoInteractions(userRepository, auditLogEventPublisher);
    }

    @Test
    void verifyEmailThrowsInvalidTokenWhenTokenExpired() {
      when(tokenRepository.findByTokenHashForUpdate(TOKEN_HASH))
          .thenReturn(Optional.of(expiredToken(UUID.randomUUID())));

      assertThatThrownBy(
              () -> registrationCommandService.verifyEmail(new VerifyEmailCommand(RAW_TOKEN)))
          .isInstanceOf(AuthException.class)
          .extracting("error")
          .isEqualTo(AuthErrorCode.AUTH_EMAIL_VERIFICATION_TOKEN_INVALID);

      verify(tokenRepository, never()).save(any());
      verifyNoInteractions(userRepository, auditLogEventPublisher);
    }

    @Test
    void verifyEmailThrowsInvalidTokenWhenTokenAlreadyUsed() {
      when(tokenRepository.findByTokenHashForUpdate(TOKEN_HASH))
          .thenReturn(Optional.of(usedToken(UUID.randomUUID())));

      assertThatThrownBy(
              () -> registrationCommandService.verifyEmail(new VerifyEmailCommand(RAW_TOKEN)))
          .isInstanceOf(AuthException.class)
          .extracting("error")
          .isEqualTo(AuthErrorCode.AUTH_EMAIL_VERIFICATION_TOKEN_INVALID);

      verify(tokenRepository, never()).save(any());
      verifyNoInteractions(userRepository, auditLogEventPublisher);
    }

    private static EmailVerificationToken pendingToken(UUID userId) {
      return EmailVerificationToken.issue(
          UUID.randomUUID(),
          userId,
          TOKEN_HASH,
          NOW.minusSeconds(60),
          Duration.ofMinutes(15),
          null);
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

  @Nested
  class ResendVerificationEmail {

    @Test
    void resendIsSilentNoOpWhenIdentifierUnknown() {
      givenThrottleAllows();
      when(userRepository.findByNormalizedIdentifier("nobody@example.com"))
          .thenReturn(Optional.empty());

      registrationCommandService.resendVerificationEmail(command("nobody@example.com"));

      verifyNoInteractions(
          tokenRepository, auditLogEventPublisher, verificationEmailEventPublisher);
    }

    @Test
    void resendIsSilentNoOpWhenAccountAlreadyActive() {
      givenThrottleAllows();
      when(userRepository.findByNormalizedIdentifier("operator1@example.com"))
          .thenReturn(Optional.of(activeUser(UUID.randomUUID())));

      registrationCommandService.resendVerificationEmail(command("operator1@example.com"));

      verifyNoInteractions(
          tokenRepository, auditLogEventPublisher, verificationEmailEventPublisher);
    }

    @Test
    void resendInvalidatesOldTokensIssuesNewTokenAndPublishesEventsWhenAccountPending() {
      UUID userId = UUID.randomUUID();
      givenThrottleAllows();
      when(userRepository.findByNormalizedIdentifier("operator1@example.com"))
          .thenReturn(Optional.of(pendingUser(userId)));
      when(verificationTokenGenerator.generate()).thenReturn("new-raw-token");
      when(tokenRepository.save(any(EmailVerificationToken.class)))
          .thenAnswer(invocation -> invocation.getArgument(0));

      TransactionSynchronizationManager.initSynchronization();

      registrationCommandService.resendVerificationEmail(command("operator1@example.com"));

      verify(tokenRepository).invalidateAllForUser(userId, NOW);

      ArgumentCaptor<EmailVerificationToken> tokenCaptor =
          ArgumentCaptor.forClass(EmailVerificationToken.class);
      verify(tokenRepository).save(tokenCaptor.capture());
      String expectedHash = HashUtils.sha256("new-raw-token".getBytes(StandardCharsets.UTF_8));
      assertThat(tokenCaptor.getValue().getTokenHash()).isEqualTo(expectedHash);
      assertThat(tokenCaptor.getValue().getTokenHash()).isNotEqualTo("new-raw-token");
      assertThat(tokenCaptor.getValue().getUserId()).isEqualTo(userId);

      verifyNoInteractions(auditLogEventPublisher, verificationEmailEventPublisher);

      runAfterCommit();

      ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
      verify(auditLogEventPublisher).publish(auditCaptor.capture());
      assertThat(auditCaptor.getValue().getOperation())
          .isEqualTo(OperationType.EMAIL_VERIFICATION_REQUESTED);
      assertThat(auditCaptor.getValue().getObjectId()).isEqualTo(userId);
      assertThat(auditCaptor.getValue().getChangedBy()).isEqualTo(userId);

      verify(verificationEmailEventPublisher)
          .publish(
              "operator1@example.com",
              "Operator One",
              "https://app.example.com/verify?token=new-raw-token");
    }

    @Test
    void resendThrowsRateLimitedBeforeIdentifierLookupWhenThrottleExceeded() {
      ResendVerificationEmailCommand command = command("operator1@example.com");
      when(authThrottle.tryConsume(eq("resend:" + command.ipAddress()), eq(5), any(Duration.class)))
          .thenReturn(false);

      assertThatThrownBy(() -> registrationCommandService.resendVerificationEmail(command))
          .isInstanceOf(AuthException.class)
          .extracting("error")
          .isEqualTo(AuthErrorCode.AUTH_RATE_LIMITED);

      verifyNoInteractions(
          userRepository,
          tokenRepository,
          auditLogEventPublisher,
          verificationTokenGenerator,
          verificationEmailEventPublisher);
    }

    private void givenThrottleAllows() {
      when(authThrottle.tryConsume(anyString(), eq(5), any(Duration.class))).thenReturn(true);
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
}
