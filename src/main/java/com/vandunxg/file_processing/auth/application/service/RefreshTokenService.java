package com.vandunxg.file_processing.auth.application.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.vandunxg.common.utils.HashUtils;
import com.vandunxg.common.utils.IdUtils;
import com.vandunxg.file_processing.auth.application.command.RefreshTokenCommand;
import com.vandunxg.file_processing.auth.application.port.in.RefreshTokenUseCase;
import com.vandunxg.file_processing.auth.application.port.out.*;
import com.vandunxg.file_processing.auth.application.result.LoginResult;
import com.vandunxg.file_processing.auth.configuration.AuthProperties;
import com.vandunxg.file_processing.auth.domain.exception.AuthDomainException;
import com.vandunxg.file_processing.auth.domain.exception.AuthErrorCode;
import com.vandunxg.file_processing.auth.domain.model.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-REFRESH")
public class RefreshTokenService implements RefreshTokenUseCase {

  private static final Duration IP_WINDOW = Duration.ofHours(1);
  private static final String IP_THROTTLE_PREFIX = "refresh:ip:";

  private final AuthThrottlePort throttlePort;
  private final SessionRepositoryPort sessionRepositoryPort;
  private final UserRepositoryPort userRepositoryPort;
  private final CredentialVersionCachePort credentialVersionCachePort;
  private final RefreshTokenGeneratorPort refreshTokenGeneratorPort;
  private final JwtIssuerPort jwtIssuerPort;
  private final AuditLogEventPublisherPort auditLogEventPublisherPort;
  private final AuthProperties authProperties;
  private final Clock clock;

  @Override
  @Transactional
  public LoginResult refresh(RefreshTokenCommand command) {
    String ipHash = hashIp(command.getIpAddress());
    if (!throttlePort.tryConsume(
        IP_THROTTLE_PREFIX + ipHash, authProperties.login().refreshIpMaxPerHour(), IP_WINDOW)) {
      log.warn("[refresh] rate limited by ip");
      throw new AuthDomainException(AuthErrorCode.AUTH_RATE_LIMITED);
    }

    String rawRefresh = command.getRefreshToken();
    if (rawRefresh == null || rawRefresh.isBlank()) {
      log.warn("[refresh] blank refresh token");
      throw new AuthDomainException(AuthErrorCode.REFRESH_TOKEN_INVALID);
    }
    String incomingHash = HashUtils.sha256(rawRefresh.getBytes(StandardCharsets.UTF_8));
    Instant now = Instant.now(clock);

    Optional<UUID> sidOpt = sessionRepositoryPort.resolveSessionIdByRefreshHash(incomingHash);
    if (sidOpt.isEmpty()) {
      Optional<UUID> reusedSid = sessionRepositoryPort.resolveReusedSessionIdByHash(incomingHash);
      if (reusedSid.isPresent()) {
        handleReuseCascade(reusedSid.get(), ipHash, now);
        log.warn("[refresh] token reuse detected sid={}", reusedSid.get());
        throw new AuthDomainException(AuthErrorCode.REFRESH_TOKEN_REUSED);
      }
      log.warn("[refresh] unknown refresh token");
      throw new AuthDomainException(AuthErrorCode.REFRESH_TOKEN_INVALID);
    }
    UUID sid = sidOpt.get();

    Session session =
        sessionRepositoryPort
            .findActiveById(sid, now)
            .orElseThrow(
                () -> {
                  log.warn("[refresh] session not active sid={}", sid);
                  return new AuthDomainException(AuthErrorCode.REFRESH_TOKEN_INVALID);
                });

    int currentCv = resolveCurrentCredentialVersion(session.getUserId());
    if (session.getCredentialVersion() != currentCv) {
      sessionRepositoryPort.revoke(sid, RevocationReason.PASSWORD_CHANGED, now);
      log.warn(
          "[refresh] credential version mismatch sid={} sessionCv={} currentCv={}",
          sid,
          session.getCredentialVersion(),
          currentCv);
      throw new AuthDomainException(AuthErrorCode.REFRESH_TOKEN_INVALID);
    }

    String newRawRefresh = refreshTokenGeneratorPort.generate();
    String newHash = HashUtils.sha256(newRawRefresh.getBytes(StandardCharsets.UTF_8));
    boolean rotated =
        sessionRepositoryPort.rotateRefresh(
            sid, incomingHash, newHash, now, session.getExpiresAt());
    if (!rotated) {
      if (sessionRepositoryPort.resolveReusedSessionIdByHash(incomingHash).isPresent()) {
        handleReuseCascade(sid, ipHash, now);
        log.warn("[refresh] token reuse detected during rotation sid={}", sid);
        throw new AuthDomainException(AuthErrorCode.REFRESH_TOKEN_REUSED);
      }
      log.warn("[refresh] concurrent rotation detected sid={}", sid);
      throw new AuthDomainException(AuthErrorCode.REFRESH_TOKEN_INVALID);
    }

    User user =
        userRepositoryPort
            .findById(session.getUserId())
            .orElseThrow(
                () -> {
                  log.warn("[refresh] user vanished sid={} userId={}", sid, session.getUserId());
                  return new AuthDomainException(AuthErrorCode.REFRESH_TOKEN_INVALID);
                });
    List<String> roleCodes =
        user.getRoles() == null ? List.of() : user.getRoles().stream().map(Role::getCode).toList();

    JwtIssuerPort.IssuedAccessToken accessToken =
        jwtIssuerPort.issue(user.getId(), sid, currentCv, roleCodes, now);

    publishAuditAfterCommit(
        AuditLog.builder()
            .id(IdUtils.nextId())
            .domain(AuditLogDomain.AUTH)
            .objectId(sid)
            .operation(OperationType.TOKEN_REFRESHED)
            .changedBy(user.getId())
            .changedAt(now)
            .ipAddress(ipHash)
            .userAgent(command.getUserAgent())
            .build());

    log.info("[refresh] token refreshed sid={} userId={}", sid, user.getId());

    return LoginResult.builder()
        .tokenType("Bearer")
        .accessToken(accessToken.token())
        .expiresIn(Duration.between(accessToken.issuedAt(), accessToken.expiresAt()).toSeconds())
        .accessTokenExpiresAt(accessToken.expiresAt())
        .refreshToken(newRawRefresh)
        .refreshExpiresIn(Duration.between(now, session.getExpiresAt()).toSeconds())
        .refreshTokenExpiresAt(session.getExpiresAt())
        .sessionId(sid)
        .userId(user.getId())
        .build();
  }

  private void handleReuseCascade(UUID sessionId, String ipHash, Instant now) {
    Optional<Session> maybe = sessionRepositoryPort.findById(sessionId);
    UUID userId = maybe.map(Session::getUserId).orElse(null);
    sessionRepositoryPort.revoke(sessionId, RevocationReason.TOKEN_REUSE, now);
    if (userId == null) {
      return;
    }
    userRepositoryPort
        .findById(userId)
        .ifPresent(
            u -> {
              u.bumpCredentialVersion(now);
              userRepositoryPort.save(u);
              credentialVersionCachePort.invalidate(userId);
            });

    publishAuditAfterCommit(
        AuditLog.builder()
            .id(IdUtils.nextId())
            .domain(AuditLogDomain.AUTH)
            .objectId(userId)
            .operation(OperationType.TOKEN_REUSE_DETECTED)
            .changedBy(userId)
            .changedAt(now)
            .ipAddress(ipHash)
            .build());
  }

  private int resolveCurrentCredentialVersion(UUID userId) {
    return credentialVersionCachePort
        .get(userId)
        .orElseGet(
            () -> {
              int cv =
                  userRepositoryPort
                      .findById(userId)
                      .map(User::getCredentialVersion)
                      .orElseThrow(
                          () -> new AuthDomainException(AuthErrorCode.REFRESH_TOKEN_INVALID));
              credentialVersionCachePort.put(userId, cv);
              return cv;
            });
  }

  private void publishAuditAfterCommit(AuditLog auditLog) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            try {
              auditLogEventPublisherPort.publish(auditLog);
            } catch (Exception e) {
              log.warn(
                  "[publishAuditAfterCommit] failed to publish audit event operation={}",
                  auditLog.getOperation(),
                  e);
            }
          }
        });
  }

  private static String hashIp(String ip) {
    return ip == null ? null : HashUtils.sha256(ip.getBytes(StandardCharsets.UTF_8));
  }
}
