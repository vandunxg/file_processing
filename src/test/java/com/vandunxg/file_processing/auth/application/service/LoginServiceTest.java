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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.vandunxg.file_processing.auth.application.command.LoginCommand;
import com.vandunxg.file_processing.auth.application.port.out.AuditLogEventPublisherPort;
import com.vandunxg.file_processing.auth.application.port.out.AuthThrottlePort;
import com.vandunxg.file_processing.auth.application.port.out.CredentialVersionCachePort;
import com.vandunxg.file_processing.auth.application.port.out.JwtIssuerPort;
import com.vandunxg.file_processing.auth.application.port.out.PasswordHasherPort;
import com.vandunxg.file_processing.auth.application.port.out.RefreshTokenGeneratorPort;
import com.vandunxg.file_processing.auth.application.port.out.SessionRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.UserRepositoryPort;
import com.vandunxg.file_processing.auth.application.result.LoginResult;
import com.vandunxg.file_processing.auth.configuration.AuthProperties;
import com.vandunxg.file_processing.auth.domain.exception.AuthDomainException;
import com.vandunxg.file_processing.auth.domain.exception.AuthErrorCode;
import com.vandunxg.file_processing.auth.domain.model.ActiveStatus;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import com.vandunxg.file_processing.auth.domain.model.OperationType;
import com.vandunxg.file_processing.auth.domain.model.Role;
import com.vandunxg.file_processing.auth.domain.model.Session;
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
class LoginServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-19T10:15:30Z");
  private static final String USERNAME = "operator01";
  private static final String PASSWORD = "MatKhauAnToan123";
  private static final String PASSWORD_HASH = "{bcrypt}hashed";

  @Mock private AuthThrottlePort throttlePort;
  @Mock private UserRepositoryPort userRepositoryPort;
  @Mock private PasswordHasherPort passwordHasherPort;
  @Mock private RefreshTokenGeneratorPort refreshTokenGeneratorPort;
  @Mock private SessionRepositoryPort sessionRepositoryPort;
  @Mock private JwtIssuerPort jwtIssuerPort;
  @Mock private AuthorityService authorityService;
  @Mock private CredentialVersionCachePort credentialVersionCachePort;
  @Mock private AuditLogEventPublisherPort auditLogEventPublisherPort;

  private LoginService loginService;

  @BeforeEach
  void setUp() {
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    loginService =
        new LoginService(
            throttlePort,
            userRepositoryPort,
            passwordHasherPort,
            refreshTokenGeneratorPort,
            sessionRepositoryPort,
            jwtIssuerPort,
            authorityService,
            credentialVersionCachePort,
            auditLogEventPublisherPort,
            authProperties(),
            clock);
  }

  @AfterEach
  void tearDown() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void loginIssuesTokensAndSessionWhenCredentialsValid() {
    User user = activeUser();
    when(throttlePort.tryConsume(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
    when(userRepositoryPort.findByNormalizedIdentifier(USERNAME)).thenReturn(Optional.of(user));
    when(passwordHasherPort.matches(PASSWORD, PASSWORD_HASH)).thenReturn(true);
    when(userRepositoryPort.save(any(User.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(refreshTokenGeneratorPort.generate()).thenReturn("raw-refresh-token");
    when(authorityService.permissionsFor(user)).thenReturn(List.of("file:self_create"));
    when(jwtIssuerPort.issue(any(), any(), anyInt(), any(), any(), any()))
        .thenReturn(
            new JwtIssuerPort.IssuedAccessToken(
                "access-token", NOW, NOW.plus(Duration.ofMinutes(15))));

    TransactionSynchronizationManager.initSynchronization();

    LoginResult result = loginService.login(loginCommand());

    assertThat(result.getTokenType()).isEqualTo("Bearer");
    assertThat(result.getAccessToken()).isEqualTo("access-token");
    assertThat(result.getRefreshToken()).isEqualTo("raw-refresh-token");
    assertThat(result.getUserId()).isEqualTo(user.getId());
    assertThat(user.getFailedLoginCount()).isZero();

    verify(sessionRepositoryPort).save(any(Session.class), anyString());
    verify(credentialVersionCachePort).put(eq(user.getId()), eq(user.getCredentialVersion()));
    verify(jwtIssuerPort)
        .issue(
            eq(user.getId()),
            any(UUID.class),
            eq(user.getCredentialVersion()),
            eq(List.of("OPERATOR")),
            eq(List.of("file:self_create")),
            eq(NOW));

    new ArrayList<>(TransactionSynchronizationManager.getSynchronizations())
        .forEach(TransactionSynchronization::afterCommit);

    ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogEventPublisherPort).publish(auditCaptor.capture());
    assertThat(auditCaptor.getValue().getOperation()).isEqualTo(OperationType.LOGIN_SUCCEEDED);
  }

  @Test
  void loginDoesNotIssueNormalTokensOrCreateASessionWhenPasswordChangeIsRequired() {
    User user = userWithStatus(UserStatus.ACTIVE, true);
    when(throttlePort.tryConsume(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
    when(userRepositoryPort.findByNormalizedIdentifier(USERNAME)).thenReturn(Optional.of(user));
    when(passwordHasherPort.matches(PASSWORD, PASSWORD_HASH)).thenReturn(true);
    when(userRepositoryPort.save(any(User.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(jwtIssuerPort.issuePasswordChange(any(), anyInt(), any()))
        .thenReturn(
            new JwtIssuerPort.IssuedPasswordChangeToken(
                "password-change-token", NOW, NOW.plus(Duration.ofMinutes(5))));
    TransactionSynchronizationManager.initSynchronization();

    LoginResult result = loginService.login(loginCommand());

    assertThat(result.getStatus()).isEqualTo("PASSWORD_CHANGE_REQUIRED");
    assertThat(result.getPasswordChangeToken()).isNotBlank();
    assertThat(result.getAccessToken()).isNull();
    assertThat(result.getRefreshToken()).isNull();
    verify(sessionRepositoryPort, never()).save(any(Session.class), anyString());
    verify(jwtIssuerPort, never()).issue(any(), any(), anyInt(), any(), any(), any());
    verify(jwtIssuerPort).issuePasswordChange(user.getId(), user.getCredentialVersion(), NOW);
    verifyNoInteractions(refreshTokenGeneratorPort);

    new ArrayList<>(TransactionSynchronizationManager.getSynchronizations())
        .forEach(TransactionSynchronization::afterCommit);

    ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogEventPublisherPort).publish(auditCaptor.capture());
    assertThat(auditCaptor.getValue().getOperation()).isEqualTo(OperationType.LOGIN_SUCCEEDED);
  }

  @Test
  void loginThrowsInvalidCredentialsWhenUserNotFound() {
    when(throttlePort.tryConsume(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
    when(userRepositoryPort.findByNormalizedIdentifier(USERNAME)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> loginService.login(loginCommand()))
        .isInstanceOf(AuthDomainException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.INVALID_CREDENTIALS);

    verifyNoInteractions(passwordHasherPort, sessionRepositoryPort, jwtIssuerPort);
  }

  @Test
  void loginThrowsAccountLockedWhenUserIsLocked() {
    User user = activeUser();
    user.registerFailedLogin(NOW.minusSeconds(30), 1, Duration.ofMinutes(15));
    when(throttlePort.tryConsume(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
    when(userRepositoryPort.findByNormalizedIdentifier(USERNAME)).thenReturn(Optional.of(user));

    assertThatThrownBy(() -> loginService.login(loginCommand()))
        .isInstanceOf(AuthDomainException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.ACCOUNT_LOCKED);

    verifyNoInteractions(passwordHasherPort, sessionRepositoryPort, jwtIssuerPort);
  }

  @Test
  void loginRecordsFailureAndThrowsInvalidCredentialsWhenPasswordWrong() {
    User user = activeUser();
    when(throttlePort.tryConsume(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
    when(userRepositoryPort.findByNormalizedIdentifier(USERNAME)).thenReturn(Optional.of(user));
    when(passwordHasherPort.matches(PASSWORD, PASSWORD_HASH)).thenReturn(false);
    when(userRepositoryPort.save(any(User.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    TransactionSynchronizationManager.initSynchronization();

    assertThatThrownBy(() -> loginService.login(loginCommand()))
        .isInstanceOf(AuthDomainException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.INVALID_CREDENTIALS);

    assertThat(user.getFailedLoginCount()).isEqualTo(1);
    verifyNoInteractions(sessionRepositoryPort, jwtIssuerPort);

    new ArrayList<>(TransactionSynchronizationManager.getSynchronizations())
        .forEach(TransactionSynchronization::afterCommit);

    ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogEventPublisherPort).publish(auditCaptor.capture());
    assertThat(auditCaptor.getValue().getOperation()).isEqualTo(OperationType.LOGIN_FAILED);
  }

  @Test
  void loginPublishesAccountLockedOutAuditWhenFailureCrossesLockThreshold() {
    User user = activeUser();
    user.registerFailedLogin(NOW.minusSeconds(30), 5, Duration.ofMinutes(15));
    user.registerFailedLogin(NOW.minusSeconds(20), 5, Duration.ofMinutes(15));
    user.registerFailedLogin(NOW.minusSeconds(10), 5, Duration.ofMinutes(15));
    user.registerFailedLogin(NOW.minusSeconds(5), 5, Duration.ofMinutes(15));
    // failedLoginCount == 4 now, one more failure reaches maxFailures(5) and locks.
    when(throttlePort.tryConsume(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
    when(userRepositoryPort.findByNormalizedIdentifier(USERNAME)).thenReturn(Optional.of(user));
    when(passwordHasherPort.matches(PASSWORD, PASSWORD_HASH)).thenReturn(false);
    when(userRepositoryPort.save(any(User.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    TransactionSynchronizationManager.initSynchronization();

    assertThatThrownBy(() -> loginService.login(loginCommand()))
        .isInstanceOf(AuthDomainException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.INVALID_CREDENTIALS);

    assertThat(user.isLocked(NOW)).isTrue();

    new ArrayList<>(TransactionSynchronizationManager.getSynchronizations())
        .forEach(TransactionSynchronization::afterCommit);

    ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogEventPublisherPort, org.mockito.Mockito.times(2)).publish(auditCaptor.capture());
    List<OperationType> operations =
        auditCaptor.getAllValues().stream().map(AuditLog::getOperation).toList();
    assertThat(operations)
        .containsExactly(OperationType.LOGIN_FAILED, OperationType.ACCOUNT_LOCKED_OUT);
  }

  @Test
  void loginThrowsEmailVerificationRequiredWhenPasswordCorrectButUserPendingVerify() {
    User user = pendingVerifyUser();
    when(throttlePort.tryConsume(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
    when(userRepositoryPort.findByNormalizedIdentifier(USERNAME)).thenReturn(Optional.of(user));
    when(passwordHasherPort.matches(PASSWORD, PASSWORD_HASH)).thenReturn(true);

    assertThatThrownBy(() -> loginService.login(loginCommand()))
        .isInstanceOf(AuthDomainException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.EMAIL_VERIFICATION_REQUIRED);

    verify(userRepositoryPort, never()).save(any());
    verifyNoInteractions(sessionRepositoryPort, jwtIssuerPort, auditLogEventPublisherPort);
  }

  @Test
  void loginThrowsInvalidCredentialsWhenPendingVerifyUserGivesWrongPassword() {
    User user = pendingVerifyUser();
    when(throttlePort.tryConsume(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
    when(userRepositoryPort.findByNormalizedIdentifier(USERNAME)).thenReturn(Optional.of(user));
    when(passwordHasherPort.matches(PASSWORD, PASSWORD_HASH)).thenReturn(false);
    when(userRepositoryPort.save(any(User.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    assertThatThrownBy(() -> loginService.login(loginCommand()))
        .isInstanceOf(AuthDomainException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.INVALID_CREDENTIALS);
  }

  @Test
  void loginThrowsInvalidCredentialsWhenAccountDisabledEvenWithCorrectPassword() {
    User user = disabledUser();
    when(throttlePort.tryConsume(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
    when(userRepositoryPort.findByNormalizedIdentifier(USERNAME)).thenReturn(Optional.of(user));
    when(passwordHasherPort.matches(PASSWORD, PASSWORD_HASH)).thenReturn(true);

    assertThatThrownBy(() -> loginService.login(loginCommand()))
        .isInstanceOf(AuthDomainException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.INVALID_CREDENTIALS);

    verify(userRepositoryPort, never()).save(any());
    verifyNoInteractions(sessionRepositoryPort, jwtIssuerPort);
  }

  @Test
  void loginThrowsRateLimitedWhenIpThrottleDenies() {
    when(throttlePort.tryConsume(eq("login:ip:" + null), anyInt(), any(Duration.class)))
        .thenReturn(false);

    assertThatThrownBy(() -> loginService.login(loginCommand()))
        .isInstanceOf(AuthDomainException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.AUTH_RATE_LIMITED);

    verifyNoInteractions(userRepositoryPort, passwordHasherPort);
  }

  private static LoginCommand loginCommand() {
    return LoginCommand.builder()
        .username(USERNAME)
        .password(PASSWORD)
        .userAgent("JUnit")
        .ipAddress(null)
        .build();
  }

  private static User activeUser() {
    return userWithStatus(UserStatus.ACTIVE);
  }

  private static User pendingVerifyUser() {
    return userWithStatus(UserStatus.PENDING_VERIFY);
  }

  private static User disabledUser() {
    return userWithStatus(UserStatus.DISABLED);
  }

  private static User userWithStatus(UserStatus status) {
    return userWithStatus(status, false);
  }

  private static User userWithStatus(UserStatus status, boolean mustChangePassword) {
    return User.builder()
        .id(UUID.randomUUID())
        .username(USERNAME)
        .normalizedUsername(USERNAME)
        .email(USERNAME + "@example.com")
        .normalizedEmail(USERNAME + "@example.com")
        .displayName("Operator One")
        .passwordHash(PASSWORD_HASH)
        .status(status)
        .roles(Set.of(operatorRole()))
        .mustChangePassword(mustChangePassword)
        .credentialVersion(1)
        .failedLoginCount(0)
        .build();
  }

  private static Role operatorRole() {
    return Role.builder()
        .id(UUID.randomUUID())
        .code("OPERATOR")
        .status(ActiveStatus.ACTIVE)
        .build();
  }

  private static AuthProperties authProperties() {
    AuthProperties.Login login =
        new AuthProperties.Login(
            100,
            100,
            Duration.ofMinutes(15),
            100,
            5,
            Duration.ofMinutes(15),
            Duration.ofMinutes(15));
    AuthProperties.Refresh refresh = new AuthProperties.Refresh(Duration.ofDays(7));
    return new AuthProperties(null, null, login, refresh, null, null, null, null, null);
  }
}
