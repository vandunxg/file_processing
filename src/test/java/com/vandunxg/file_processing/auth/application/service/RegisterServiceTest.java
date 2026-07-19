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
import com.vandunxg.file_processing.auth.application.command.RegisterCommand;
import com.vandunxg.file_processing.auth.application.port.out.AuditLogEventPublisherPort;
import com.vandunxg.file_processing.auth.application.port.out.AuthThrottlePort;
import com.vandunxg.file_processing.auth.application.port.out.EmailVerificationTokenRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.PasswordHasherPort;
import com.vandunxg.file_processing.auth.application.port.out.RoleRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.UserRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.UserRoleRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.VerificationEmailEventPublisherPort;
import com.vandunxg.file_processing.auth.application.port.out.VerificationTokenGeneratorPort;
import com.vandunxg.file_processing.auth.application.result.RegisterResult;
import com.vandunxg.file_processing.auth.configuration.AuthProperties;
import com.vandunxg.file_processing.auth.domain.exception.AuthDomainException;
import com.vandunxg.file_processing.auth.domain.exception.AuthErrorCode;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class RegisterServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-17T10:15:30Z");

  @Mock private AuthThrottlePort throttlePort;
  @Mock private UserRepositoryPort userRepositoryPort;
  @Mock private RoleRepositoryPort roleRepositoryPort;
  @Mock private UserRoleRepositoryPort userRoleRepositoryPort;
  @Mock private AuditLogEventPublisherPort auditLogEventPublisherPort;
  @Mock private EmailVerificationTokenRepositoryPort tokenRepositoryPort;
  @Mock private PasswordHasherPort passwordHasherPort;
  @Mock private VerificationTokenGeneratorPort tokenGeneratorPort;
  @Mock private VerificationEmailEventPublisherPort verificationEmailEventPublisherPort;

  private RegisterService registerService;

  @BeforeEach
  void setUp() {
    AuthProperties authProperties = AuthPropertiesFixture.defaults();
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    registerService =
        new RegisterService(
            throttlePort,
            userRepositoryPort,
            roleRepositoryPort,
            userRoleRepositoryPort,
            auditLogEventPublisherPort,
            tokenRepositoryPort,
            passwordHasherPort,
            tokenGeneratorPort,
            verificationEmailEventPublisherPort,
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
  void registerReturnsPendingVerifyResultAndPublishesEventsAfterCommitWhenValid() {
    Role operatorRole = operatorRole();
    RegisterCommand command = validCommand();
    givenThrottleAllows();
    givenNoExistingUsernameOrEmail();
    when(roleRepositoryPort.findByCode("OPERATOR")).thenReturn(Optional.of(operatorRole));
    when(passwordHasherPort.hash(command.getPassword())).thenReturn("{bcrypt}hashed");
    when(userRepositoryPort.save(any(User.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(userRoleRepositoryPort.save(any(UserRole.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(tokenGeneratorPort.generate()).thenReturn("raw-verification-token");
    when(tokenRepositoryPort.save(any(EmailVerificationToken.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    TransactionSynchronizationManager.initSynchronization();

    RegisterResult result = registerService.register(command);

    assertThat(result.getStatus()).isEqualTo(UserStatus.PENDING_VERIFY);
    assertThat(result.getUsername()).isEqualTo("operator1");
    assertThat(result.getEmail()).isEqualTo("operator1@example.com");
    assertThat(result.getDisplayName()).isEqualTo("Operator One");
    assertThat(result.getId()).isNotNull();

    ArgumentCaptor<UserRole> userRoleCaptor = ArgumentCaptor.forClass(UserRole.class);
    verify(userRoleRepositoryPort).save(userRoleCaptor.capture());
    assertThat(userRoleCaptor.getValue().getUserId()).isEqualTo(result.getId());
    assertThat(userRoleCaptor.getValue().getRoleId()).isEqualTo(operatorRole.getId());

    ArgumentCaptor<EmailVerificationToken> tokenCaptor =
        ArgumentCaptor.forClass(EmailVerificationToken.class);
    verify(tokenRepositoryPort).save(tokenCaptor.capture());
    String expectedHash =
        HashUtils.sha256("raw-verification-token".getBytes(StandardCharsets.UTF_8));
    assertThat(tokenCaptor.getValue().getTokenHash()).isEqualTo(expectedHash);
    assertThat(tokenCaptor.getValue().getTokenHash()).isNotEqualTo("raw-verification-token");
    assertThat(tokenCaptor.getValue().getUserId()).isEqualTo(result.getId());

    verifyNoInteractions(auditLogEventPublisherPort, verificationEmailEventPublisherPort);

    new ArrayList<>(TransactionSynchronizationManager.getSynchronizations())
        .forEach(TransactionSynchronization::afterCommit);

    ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogEventPublisherPort).publish(auditCaptor.capture());
    assertThat(auditCaptor.getValue().getOperation()).isEqualTo(OperationType.USER_REGISTERED);
    assertThat(auditCaptor.getValue().getObjectId()).isEqualTo(result.getId());
    assertThat(auditCaptor.getValue().getChangedBy()).isEqualTo(result.getId());

    verify(verificationEmailEventPublisherPort)
        .publish(
            "operator1@example.com",
            "Operator One",
            "https://app.example.com/verify?token=raw-verification-token");
  }

  @Test
  void registerThrowsUsernameAlreadyExistsWhenUsernameAlreadyTaken() {
    RegisterCommand command = validCommand();
    givenThrottleAllows();
    when(userRepositoryPort.existsByNormalizedUsername("operator1")).thenReturn(true);

    assertThatThrownBy(() -> registerService.register(command))
        .isInstanceOf(AuthDomainException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.USERNAME_ALREADY_EXISTS);

    verify(passwordHasherPort, never()).hash(anyString());
    verify(tokenGeneratorPort, never()).generate();
    verify(userRepositoryPort, never()).save(any());
  }

  @Test
  void registerThrowsEmailAlreadyExistsWhenEmailAlreadyTaken() {
    RegisterCommand command = validCommand();
    givenThrottleAllows();
    when(userRepositoryPort.existsByNormalizedUsername("operator1")).thenReturn(false);
    when(userRepositoryPort.existsByNormalizedEmail("operator1@example.com")).thenReturn(true);

    assertThatThrownBy(() -> registerService.register(command))
        .isInstanceOf(AuthDomainException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.EMAIL_ALREADY_EXISTS);

    verify(passwordHasherPort, never()).hash(anyString());
    verify(tokenGeneratorPort, never()).generate();
    verify(userRepositoryPort, never()).save(any());
  }

  @Test
  void registerTranslatesConcurrentSaveFailureToUsernameAlreadyExistsWhenUsernameConstraintFires() {
    RegisterCommand command = validCommand();
    givenThrottleAllows();
    givenNoExistingUsernameOrEmail();
    when(roleRepositoryPort.findByCode("OPERATOR")).thenReturn(Optional.of(operatorRole()));
    when(passwordHasherPort.hash(command.getPassword())).thenReturn("{bcrypt}hashed");
    when(userRepositoryPort.save(any(User.class)))
        .thenThrow(
            new DataIntegrityViolationException(
                "duplicate key value violates unique constraint"
                    + " \"auth_users_normalized_username_uk\""));

    assertThatThrownBy(() -> registerService.register(command))
        .isInstanceOf(AuthDomainException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.USERNAME_ALREADY_EXISTS);

    verifyNoInteractions(
        userRoleRepositoryPort,
        tokenRepositoryPort,
        auditLogEventPublisherPort,
        verificationEmailEventPublisherPort);
  }

  @Test
  void registerTranslatesConcurrentSaveFailureToEmailAlreadyExistsWhenEmailConstraintFires() {
    RegisterCommand command = validCommand();
    givenThrottleAllows();
    givenNoExistingUsernameOrEmail();
    when(roleRepositoryPort.findByCode("OPERATOR")).thenReturn(Optional.of(operatorRole()));
    when(passwordHasherPort.hash(command.getPassword())).thenReturn("{bcrypt}hashed");
    when(userRepositoryPort.save(any(User.class)))
        .thenThrow(
            new DataIntegrityViolationException(
                "duplicate key value violates unique constraint"
                    + " \"auth_users_normalized_email_uk\""));

    assertThatThrownBy(() -> registerService.register(command))
        .isInstanceOf(AuthDomainException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.EMAIL_ALREADY_EXISTS);

    verifyNoInteractions(
        userRoleRepositoryPort,
        tokenRepositoryPort,
        auditLogEventPublisherPort,
        verificationEmailEventPublisherPort);
  }

  @Test
  void registerDefaultsToUsernameAlreadyExistsWhenConstraintNameIsUnparseable() {
    RegisterCommand command = validCommand();
    givenThrottleAllows();
    givenNoExistingUsernameOrEmail();
    when(roleRepositoryPort.findByCode("OPERATOR")).thenReturn(Optional.of(operatorRole()));
    when(passwordHasherPort.hash(command.getPassword())).thenReturn("{bcrypt}hashed");
    when(userRepositoryPort.save(any(User.class)))
        .thenThrow(new DataIntegrityViolationException("connection reset by peer"));

    assertThatThrownBy(() -> registerService.register(command))
        .isInstanceOf(AuthDomainException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.USERNAME_ALREADY_EXISTS);
  }

  @Test
  void registerThrowsPasswordPolicyViolationWhenPasswordTooShortAndNeverHashesOrGeneratesToken() {
    RegisterCommand command =
        RegisterCommand.builder()
            .username("operator1")
            .email("operator1@example.com")
            .displayName("Operator One")
            .password("short1")
            .ipAddress("203.0.113.5")
            .build();
    givenThrottleAllows();

    assertThatThrownBy(() -> registerService.register(command))
        .isInstanceOf(AuthDomainException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.PASSWORD_POLICY_VIOLATION);

    verify(passwordHasherPort, never()).hash(anyString());
    verify(tokenGeneratorPort, never()).generate();
    verifyNoInteractions(
        userRepositoryPort,
        roleRepositoryPort,
        userRoleRepositoryPort,
        tokenRepositoryPort,
        auditLogEventPublisherPort,
        verificationEmailEventPublisherPort);
  }

  @Test
  void registerThrowsRateLimitedBeforeAnyOtherCheckWhenThrottleExceeded() {
    RegisterCommand command = validCommand();
    when(throttlePort.tryConsume(
            eq("register:" + command.getIpAddress()), eq(5), any(Duration.class)))
        .thenReturn(false);

    assertThatThrownBy(() -> registerService.register(command))
        .isInstanceOf(AuthDomainException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.AUTH_RATE_LIMITED);

    verifyNoInteractions(
        roleRepositoryPort,
        userRepositoryPort,
        passwordHasherPort,
        tokenGeneratorPort,
        userRoleRepositoryPort,
        tokenRepositoryPort,
        auditLogEventPublisherPort,
        verificationEmailEventPublisherPort);
  }

  @Test
  void registerThrowsInvalidRoleWhenOperatorRoleNotFound() {
    RegisterCommand command = validCommand();
    givenThrottleAllows();
    givenNoExistingUsernameOrEmail();
    when(roleRepositoryPort.findByCode("OPERATOR")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> registerService.register(command))
        .isInstanceOf(AuthDomainException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.INVALID_ROLE);

    verify(passwordHasherPort, never()).hash(anyString());
    verify(tokenGeneratorPort, never()).generate();
  }

  private void givenThrottleAllows() {
    when(throttlePort.tryConsume(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
  }

  private void givenNoExistingUsernameOrEmail() {
    when(userRepositoryPort.existsByNormalizedUsername(anyString())).thenReturn(false);
    when(userRepositoryPort.existsByNormalizedEmail(anyString())).thenReturn(false);
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
