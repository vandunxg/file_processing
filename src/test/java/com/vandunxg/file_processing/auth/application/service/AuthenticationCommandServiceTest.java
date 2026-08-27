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

import com.vandunxg.file_processing.auth.application.AuditTrail;
import com.vandunxg.file_processing.auth.application.AuthProperties;
import com.vandunxg.file_processing.auth.application.capability.AuditLogEventPublisher;
import com.vandunxg.file_processing.auth.application.capability.AuthMetrics;
import com.vandunxg.file_processing.auth.application.capability.AuthThrottle;
import com.vandunxg.file_processing.auth.application.capability.CredentialVersionCache;
import com.vandunxg.file_processing.auth.application.capability.PasswordHasher;
import com.vandunxg.file_processing.auth.application.capability.RefreshTokenGenerator;
import com.vandunxg.file_processing.auth.application.capability.TokenIssuer;
import com.vandunxg.file_processing.auth.application.command.LoginCommand;
import com.vandunxg.file_processing.auth.application.exception.AuthErrorCode;
import com.vandunxg.file_processing.auth.application.exception.AuthException;
import com.vandunxg.file_processing.auth.application.result.LoginResult;
import com.vandunxg.file_processing.auth.domain.SessionRepository;
import com.vandunxg.file_processing.auth.domain.UserRepository;
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
class AuthenticationCommandServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-19T10:15:30Z");
  private static final String USERNAME = "operator01";
  private static final String PASSWORD = "MatKhauAnToan123";
  private static final String PASSWORD_HASH = "{bcrypt}hashed";

  @Mock private AuthThrottle authThrottle;
  @Mock private UserRepository userRepository;
  @Mock private PasswordHasher passwordHasher;
  @Mock private RefreshTokenGenerator refreshTokenGenerator;
  @Mock private SessionRepository sessionRepository;
  @Mock private TokenIssuer tokenIssuer;
  @Mock private AuthorityService authorityService;
  @Mock private CredentialVersionCache credentialVersionCache;
  @Mock private AuditLogEventPublisher auditLogEventPublisher;
  @Mock private AuthMetrics authMetrics;

  private AuthenticationCommandService authenticationCommandService;

  @BeforeEach
  void setUp() {
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    authenticationCommandService =
        new AuthenticationCommandService(
            authThrottle,
            userRepository,
            passwordHasher,
            refreshTokenGenerator,
            sessionRepository,
            tokenIssuer,
            authorityService,
            credentialVersionCache,
            new AuditTrail(auditLogEventPublisher),
            authMetrics,
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
    when(authThrottle.tryConsume(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
    when(userRepository.findByNormalizedIdentifier(USERNAME)).thenReturn(Optional.of(user));
    when(passwordHasher.matches(PASSWORD, PASSWORD_HASH)).thenReturn(true);
    when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(refreshTokenGenerator.generate()).thenReturn("raw-refresh-token");
    when(authorityService.permissionsFor(user)).thenReturn(List.of("file:self_create"));
    when(tokenIssuer.issue(any(), any(), anyInt(), any(), any(), any()))
        .thenReturn(
            new TokenIssuer.IssuedAccessToken(
                "access-token", NOW, NOW.plus(Duration.ofMinutes(15))));

    TransactionSynchronizationManager.initSynchronization();

    LoginResult result = authenticationCommandService.login(loginCommand());

    assertThat(result.getTokenType()).isEqualTo("Bearer");
    assertThat(result.getAccessToken()).isEqualTo("access-token");
    assertThat(result.getRefreshToken()).isEqualTo("raw-refresh-token");
    assertThat(result.getUserId()).isEqualTo(user.getId());
    assertThat(user.getFailedLoginCount()).isZero();

    verify(sessionRepository).save(any(Session.class), anyString());
    verify(credentialVersionCache).put(eq(user.getId()), eq(user.getCredentialVersion()));
    verify(tokenIssuer)
        .issue(
            eq(user.getId()),
            any(UUID.class),
            eq(user.getCredentialVersion()),
            eq(List.of("OPERATOR")),
            eq(List.of("file:self_create")),
            eq(NOW));
    verify(authMetrics).loginSucceeded();

    new ArrayList<>(TransactionSynchronizationManager.getSynchronizations())
        .forEach(TransactionSynchronization::afterCommit);

    ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogEventPublisher).publish(auditCaptor.capture());
    assertThat(auditCaptor.getValue().getOperation()).isEqualTo(OperationType.LOGIN_SUCCEEDED);
  }

  @Test
  void loginDoesNotIssueNormalTokensOrCreateASessionWhenPasswordChangeIsRequired() {
    User user = userWithStatus(UserStatus.ACTIVE, true);
    when(authThrottle.tryConsume(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
    when(userRepository.findByNormalizedIdentifier(USERNAME)).thenReturn(Optional.of(user));
    when(passwordHasher.matches(PASSWORD, PASSWORD_HASH)).thenReturn(true);
    when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(tokenIssuer.issuePasswordChange(any(), anyInt(), any()))
        .thenReturn(
            new TokenIssuer.IssuedPasswordChangeToken(
                "password-change-token", NOW, NOW.plus(Duration.ofMinutes(5))));
    TransactionSynchronizationManager.initSynchronization();

    LoginResult result = authenticationCommandService.login(loginCommand());

    assertThat(result.getStatus()).isEqualTo("PASSWORD_CHANGE_REQUIRED");
    assertThat(result.getPasswordChangeToken()).isNotBlank();
    assertThat(result.getAccessToken()).isNull();
    assertThat(result.getRefreshToken()).isNull();
    verify(sessionRepository, never()).save(any(Session.class), anyString());
    verify(tokenIssuer, never()).issue(any(), any(), anyInt(), any(), any(), any());
    verify(tokenIssuer).issuePasswordChange(user.getId(), user.getCredentialVersion(), NOW);
    verifyNoInteractions(refreshTokenGenerator);
    verify(authMetrics).loginSucceeded();

    new ArrayList<>(TransactionSynchronizationManager.getSynchronizations())
        .forEach(TransactionSynchronization::afterCommit);

    ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogEventPublisher).publish(auditCaptor.capture());
    assertThat(auditCaptor.getValue().getOperation()).isEqualTo(OperationType.LOGIN_SUCCEEDED);
  }

  @Test
  void loginThrowsInvalidCredentialsWhenUserNotFound() {
    when(authThrottle.tryConsume(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
    when(userRepository.findByNormalizedIdentifier(USERNAME)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> authenticationCommandService.login(loginCommand()))
        .isInstanceOf(AuthException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.AUTH_INVALID_CREDENTIALS);

    verifyNoInteractions(passwordHasher, sessionRepository, tokenIssuer);
    verify(authMetrics).loginInvalidCredentials();
  }

  @Test
  void loginThrowsAccountLockedWhenUserIsLocked() {
    User user = activeUser();
    user.registerFailedLogin(NOW.minusSeconds(30), 1, Duration.ofMinutes(15));
    when(authThrottle.tryConsume(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
    when(userRepository.findByNormalizedIdentifier(USERNAME)).thenReturn(Optional.of(user));

    assertThatThrownBy(() -> authenticationCommandService.login(loginCommand()))
        .isInstanceOf(AuthException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.AUTH_ACCOUNT_LOCKED);

    verifyNoInteractions(passwordHasher, sessionRepository, tokenIssuer);
    verify(authMetrics).loginLocked();
  }

  @Test
  void loginRecordsFailureAndThrowsInvalidCredentialsWhenPasswordWrong() {
    User user = activeUser();
    when(authThrottle.tryConsume(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
    when(userRepository.findByNormalizedIdentifier(USERNAME)).thenReturn(Optional.of(user));
    when(passwordHasher.matches(PASSWORD, PASSWORD_HASH)).thenReturn(false);
    when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

    TransactionSynchronizationManager.initSynchronization();

    assertThatThrownBy(() -> authenticationCommandService.login(loginCommand()))
        .isInstanceOf(AuthException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.AUTH_INVALID_CREDENTIALS);

    assertThat(user.getFailedLoginCount()).isEqualTo(1);
    verifyNoInteractions(sessionRepository, tokenIssuer);
    verify(authMetrics).loginInvalidCredentials();

    new ArrayList<>(TransactionSynchronizationManager.getSynchronizations())
        .forEach(TransactionSynchronization::afterCommit);

    ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogEventPublisher).publish(auditCaptor.capture());
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
    when(authThrottle.tryConsume(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
    when(userRepository.findByNormalizedIdentifier(USERNAME)).thenReturn(Optional.of(user));
    when(passwordHasher.matches(PASSWORD, PASSWORD_HASH)).thenReturn(false);
    when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

    TransactionSynchronizationManager.initSynchronization();

    assertThatThrownBy(() -> authenticationCommandService.login(loginCommand()))
        .isInstanceOf(AuthException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.AUTH_INVALID_CREDENTIALS);

    assertThat(user.isLocked(NOW)).isTrue();

    new ArrayList<>(TransactionSynchronizationManager.getSynchronizations())
        .forEach(TransactionSynchronization::afterCommit);

    ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogEventPublisher, org.mockito.Mockito.times(2)).publish(auditCaptor.capture());
    List<OperationType> operations =
        auditCaptor.getAllValues().stream().map(AuditLog::getOperation).toList();
    assertThat(operations)
        .containsExactly(OperationType.LOGIN_FAILED, OperationType.ACCOUNT_LOCKED_OUT);
  }

  @Test
  void loginThrowsEmailVerificationRequiredWhenPasswordCorrectButUserPendingVerify() {
    User user = pendingVerifyUser();
    when(authThrottle.tryConsume(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
    when(userRepository.findByNormalizedIdentifier(USERNAME)).thenReturn(Optional.of(user));
    when(passwordHasher.matches(PASSWORD, PASSWORD_HASH)).thenReturn(true);

    assertThatThrownBy(() -> authenticationCommandService.login(loginCommand()))
        .isInstanceOf(AuthException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.AUTH_EMAIL_VERIFICATION_REQUIRED);

    verify(userRepository, never()).save(any());
    verifyNoInteractions(sessionRepository, tokenIssuer, auditLogEventPublisher);
    verify(authMetrics).loginPendingVerification();
  }

  @Test
  void loginThrowsInvalidCredentialsWhenPendingVerifyUserGivesWrongPassword() {
    User user = pendingVerifyUser();
    when(authThrottle.tryConsume(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
    when(userRepository.findByNormalizedIdentifier(USERNAME)).thenReturn(Optional.of(user));
    when(passwordHasher.matches(PASSWORD, PASSWORD_HASH)).thenReturn(false);
    when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

    assertThatThrownBy(() -> authenticationCommandService.login(loginCommand()))
        .isInstanceOf(AuthException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.AUTH_INVALID_CREDENTIALS);
  }

  @Test
  void loginThrowsInvalidCredentialsWhenAccountDisabledEvenWithCorrectPassword() {
    User user = disabledUser();
    when(authThrottle.tryConsume(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
    when(userRepository.findByNormalizedIdentifier(USERNAME)).thenReturn(Optional.of(user));
    when(passwordHasher.matches(PASSWORD, PASSWORD_HASH)).thenReturn(true);

    assertThatThrownBy(() -> authenticationCommandService.login(loginCommand()))
        .isInstanceOf(AuthException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.AUTH_INVALID_CREDENTIALS);

    verify(userRepository, never()).save(any());
    verifyNoInteractions(sessionRepository, tokenIssuer);
    verify(authMetrics).loginDisabled();
  }

  @Test
  void loginThrowsRateLimitedWhenIpThrottleDenies() {
    when(authThrottle.tryConsume(eq("login:ip:" + null), anyInt(), any(Duration.class)))
        .thenReturn(false);

    assertThatThrownBy(() -> authenticationCommandService.login(loginCommand()))
        .isInstanceOf(AuthException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.AUTH_RATE_LIMITED);

    verifyNoInteractions(userRepository, passwordHasher);
    verify(authMetrics).loginRateLimited();
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
