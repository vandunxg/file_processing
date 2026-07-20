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

import com.vandunxg.file_processing.auth.application.command.RefreshTokenCommand;
import com.vandunxg.file_processing.auth.application.port.out.AuditLogEventPublisherPort;
import com.vandunxg.file_processing.auth.application.port.out.AuthThrottlePort;
import com.vandunxg.file_processing.auth.application.port.out.CredentialVersionCachePort;
import com.vandunxg.file_processing.auth.application.port.out.JwtIssuerPort;
import com.vandunxg.file_processing.auth.application.port.out.RefreshTokenGeneratorPort;
import com.vandunxg.file_processing.auth.application.port.out.SessionRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.UserRepositoryPort;
import com.vandunxg.file_processing.auth.configuration.AuthProperties;
import com.vandunxg.file_processing.auth.domain.exception.AuthDomainException;
import com.vandunxg.file_processing.auth.domain.exception.AuthErrorCode;
import com.vandunxg.file_processing.auth.domain.model.RevocationReason;
import com.vandunxg.file_processing.auth.domain.model.Session;
import com.vandunxg.file_processing.auth.domain.model.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RefreshTokenServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-20T11:30:00Z");

  @Mock private AuditLogEventPublisherPort auditLogEventPublisherPort;
  @Mock private AuthThrottlePort throttlePort;
  @Mock private CredentialVersionCachePort credentialVersionCachePort;
  @Mock private JwtIssuerPort jwtIssuerPort;
  @Mock private AuthorityService authorityService;
  @Mock private RefreshTokenGeneratorPort refreshTokenGeneratorPort;
  @Mock private SessionRepositoryPort sessionRepositoryPort;
  @Mock private UserRepositoryPort userRepositoryPort;

  private RefreshTokenService refreshTokenService;

  @BeforeEach
  void setUp() {
    refreshTokenService =
        new RefreshTokenService(
            throttlePort,
            sessionRepositoryPort,
            userRepositoryPort,
            credentialVersionCachePort,
            refreshTokenGeneratorPort,
            jwtIssuerPort,
            authorityService,
            auditLogEventPublisherPort,
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
    when(throttlePort.tryConsume(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
    when(sessionRepositoryPort.resolveReusedSessionIdByHash(anyString()))
        .thenReturn(Optional.of(sessionId));
    when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
    when(userRepositoryPort.findById(userId)).thenReturn(Optional.of(user));

    assertThatThrownBy(() -> refreshTokenService.refresh(refreshCommand()))
        .isInstanceOf(AuthDomainException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.REFRESH_TOKEN_REUSED);

    assertThat(user.getCredentialVersion()).isEqualTo(2);
    verify(sessionRepositoryPort).revoke(eq(sessionId), eq(RevocationReason.TOKEN_REUSE), eq(NOW));
    verify(sessionRepositoryPort, never())
        .revokeAllForUser(any(UUID.class), eq(RevocationReason.TOKEN_REUSE), eq(NOW));
    verify(userRepositoryPort).save(user);
    verify(credentialVersionCachePort).invalidate(userId);
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
    when(throttlePort.tryConsume(anyString(), anyInt(), any(Duration.class))).thenReturn(true);
    when(sessionRepositoryPort.resolveSessionIdByRefreshHash(anyString()))
        .thenReturn(Optional.of(sessionId));
    when(sessionRepositoryPort.findActiveById(sessionId, NOW)).thenReturn(Optional.of(session));
    when(credentialVersionCachePort.get(userId)).thenReturn(Optional.of(1));
    when(refreshTokenGeneratorPort.generate()).thenReturn("replacement-token");
    when(sessionRepositoryPort.resolveReusedSessionIdByHash(anyString()))
        .thenReturn(Optional.of(sessionId));
    when(sessionRepositoryPort.findById(sessionId)).thenReturn(Optional.of(session));
    when(userRepositoryPort.findById(userId)).thenReturn(Optional.of(user));

    assertThatThrownBy(() -> refreshTokenService.refresh(refreshCommand()))
        .isInstanceOf(AuthDomainException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.REFRESH_TOKEN_REUSED);

    assertThat(user.getCredentialVersion()).isEqualTo(2);
    verify(sessionRepositoryPort).revoke(sessionId, RevocationReason.TOKEN_REUSE, NOW);
    verify(credentialVersionCachePort).invalidate(userId);
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
