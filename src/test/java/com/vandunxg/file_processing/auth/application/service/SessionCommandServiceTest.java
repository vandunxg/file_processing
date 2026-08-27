package com.vandunxg.file_processing.auth.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import com.vandunxg.file_processing.auth.application.AuditTrail;
import com.vandunxg.file_processing.auth.application.AuthProperties;
import com.vandunxg.file_processing.auth.application.capability.AuditLogEventPublisher;
import com.vandunxg.file_processing.auth.application.capability.AuthMetrics;
import com.vandunxg.file_processing.auth.application.capability.AuthThrottle;
import com.vandunxg.file_processing.auth.application.capability.CredentialVersionCache;
import com.vandunxg.file_processing.auth.application.capability.RefreshTokenGenerator;
import com.vandunxg.file_processing.auth.application.capability.TokenIssuer;
import com.vandunxg.file_processing.auth.application.command.RefreshTokenCommand;
import com.vandunxg.file_processing.auth.application.exception.AuthErrorCode;
import com.vandunxg.file_processing.auth.application.exception.AuthException;
import com.vandunxg.file_processing.auth.domain.SessionRepository;
import com.vandunxg.file_processing.auth.domain.UserRepository;
import com.vandunxg.file_processing.auth.domain.model.RevocationReason;
import com.vandunxg.file_processing.auth.domain.model.Session;
import com.vandunxg.file_processing.auth.domain.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SessionCommandServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-20T11:30:00Z");

  @Mock private AuditLogEventPublisher auditLogEventPublisher;
  @Mock private AuthThrottle authThrottle;
  @Mock private CredentialVersionCache credentialVersionCache;
  @Mock private TokenIssuer tokenIssuer;
  @Mock private AuthorityService authorityService;
  @Mock private RefreshTokenGenerator refreshTokenGenerator;
  @Mock private SessionRepository sessionRepository;
  @Mock private UserRepository userRepository;
  @Mock private AuthMetrics authMetrics;

  private SessionCommandService sessionCommandService;

  @BeforeEach
  void setUp() {
    sessionCommandService =
        new SessionCommandService(
            authThrottle,
            sessionRepository,
            userRepository,
            credentialVersionCache,
            refreshTokenGenerator,
            tokenIssuer,
            authorityService,
            new AuditTrail(auditLogEventPublisher),
            authMetrics,
            authProperties(),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void replayRevokesTheTokenFamilyAndBumpsCredentialVersion() {
    UUID sessionId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    Session session =
        Session.issue(
            sessionId,
            userId,
            1,
            "JUnit",
            null,
            NOW.minus(Duration.ofMinutes(1)),
            Duration.ofDays(7));
    User user = User.builder().id(userId).credentialVersion(1).build();
    when(authThrottle.tryConsume(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
    when(sessionRepository.resolveReusedSessionIdByHash(anyString()))
        .thenReturn(Optional.of(sessionId));
    when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));

    assertThatThrownBy(() -> sessionCommandService.refresh(refreshCommand()))
        .isInstanceOf(AuthException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.AUTH_REFRESH_TOKEN_REUSED);

    assertThat(user.getCredentialVersion()).isEqualTo(2);
    verify(sessionRepository).revoke(eq(sessionId), eq(RevocationReason.TOKEN_REUSE), eq(NOW));
    verify(sessionRepository, never())
        .revokeAllForUser(any(UUID.class), eq(RevocationReason.TOKEN_REUSE), eq(NOW));
    verify(userRepository).save(user);
    verify(credentialVersionCache).invalidate(userId);
    verify(authMetrics).refreshTokenReused();
  }

  @Test
  void concurrentRotationThatConsumesTheTokenRevokesTheFamily() {
    UUID sessionId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    Session session =
        Session.issue(
            sessionId,
            userId,
            1,
            "JUnit",
            null,
            NOW.minus(Duration.ofMinutes(1)),
            Duration.ofDays(7));
    User user = User.builder().id(userId).credentialVersion(1).build();
    when(authThrottle.tryConsume(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
    when(sessionRepository.resolveSessionIdByRefreshHash(anyString()))
        .thenReturn(Optional.of(sessionId));
    when(sessionRepository.findActiveById(sessionId, NOW)).thenReturn(Optional.of(session));
    when(credentialVersionCache.get(userId)).thenReturn(Optional.of(1));
    when(refreshTokenGenerator.generate()).thenReturn("replacement-token");
    when(sessionRepository.resolveReusedSessionIdByHash(anyString()))
        .thenReturn(Optional.of(sessionId));
    when(sessionRepository.findById(sessionId)).thenReturn(Optional.of(session));
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));

    assertThatThrownBy(() -> sessionCommandService.refresh(refreshCommand()))
        .isInstanceOf(AuthException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.AUTH_REFRESH_TOKEN_REUSED);

    assertThat(user.getCredentialVersion()).isEqualTo(2);
    verify(sessionRepository).revoke(sessionId, RevocationReason.TOKEN_REUSE, NOW);
    verify(credentialVersionCache).invalidate(userId);
    verify(authMetrics).refreshTokenReused();
  }

  @Test
  void refreshRecordsRateLimitDenials() {
    when(authThrottle.tryConsume(anyString(), anyInt(), any(Duration.class))).thenReturn(false);

    assertThatThrownBy(() -> sessionCommandService.refresh(refreshCommand()))
        .isInstanceOf(AuthException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.AUTH_RATE_LIMITED);

    verify(authMetrics).refreshRateLimited();
  }

  private static RefreshTokenCommand refreshCommand() {
    return RefreshTokenCommand.builder().refreshToken("replayed-token").build();
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
    return new AuthProperties(
        null,
        null,
        login,
        new AuthProperties.Refresh(Duration.ofDays(7)),
        null,
        null,
        null,
        null,
        null);
  }
}
