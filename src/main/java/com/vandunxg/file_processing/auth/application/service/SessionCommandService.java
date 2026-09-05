package com.vandunxg.file_processing.auth.application.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.vandunxg.common.utils.HashUtils;
import com.vandunxg.file_processing.auth.application.AfterCommit;
import com.vandunxg.file_processing.auth.application.AuditTrail;
import com.vandunxg.file_processing.auth.application.AuthProperties;
import com.vandunxg.file_processing.auth.application.capability.AuthMetrics;
import com.vandunxg.file_processing.auth.application.capability.AuthThrottle;
import com.vandunxg.file_processing.auth.application.capability.CredentialVersionCache;
import com.vandunxg.file_processing.auth.application.capability.RefreshTokenGenerator;
import com.vandunxg.file_processing.auth.application.capability.TokenIssuer;
import com.vandunxg.file_processing.auth.application.command.LogoutCommand;
import com.vandunxg.file_processing.auth.application.command.RefreshTokenCommand;
import com.vandunxg.file_processing.auth.application.command.RevokeAllSessionsCommand;
import com.vandunxg.file_processing.auth.application.command.RevokeSessionCommand;
import com.vandunxg.file_processing.auth.application.exception.AuthErrorCode;
import com.vandunxg.file_processing.auth.application.exception.AuthException;
import com.vandunxg.file_processing.auth.application.result.LoginResult;
import com.vandunxg.file_processing.auth.domain.SessionRepository;
import com.vandunxg.file_processing.auth.domain.UserRepository;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import com.vandunxg.file_processing.auth.domain.model.AuditLogDomain;
import com.vandunxg.file_processing.auth.domain.model.OperationType;
import com.vandunxg.file_processing.auth.domain.model.RevocationReason;
import com.vandunxg.file_processing.auth.domain.model.Role;
import com.vandunxg.file_processing.auth.domain.model.Session;
import com.vandunxg.file_processing.auth.domain.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refresh-session lifecycle: token rotation with reuse detection, and the three ways a session ends
 * (logout, revoking one session, revoking every session of a user).
 */
@Service
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-SESSION")
public class SessionCommandService {

  private static final String TOKEN_TYPE = "Bearer";
  private static final String IP_THROTTLE_PREFIX = "refresh:ip:";
  private static final Duration IP_WINDOW = Duration.ofHours(1);

  private final AuthThrottle authThrottle;
  private final SessionRepository sessionRepository;
  private final UserRepository userRepository;
  private final CredentialVersionCache credentialVersionCache;
  private final RefreshTokenGenerator refreshTokenGenerator;
  private final TokenIssuer tokenIssuer;
  private final AuthorityService authorityService;
  private final AuditTrail auditTrail;
  private final AuthMetrics authMetrics;
  private final AuthProperties authProperties;
  private final Clock clock;

  /**
   * Rejecting a refresh is exactly when this method writes the most: reuse detection revokes the
   * session and burns the whole credential version, and a credential-version mismatch revokes the
   * stale session. {@link AuthException} is unchecked, so the default rollback rule would undo all
   * of it and leave the attacker's session live. Failures of this type therefore commit.
   */
  @Transactional(noRollbackFor = AuthException.class)
  public LoginResult refresh(RefreshTokenCommand command) {
    String ipHash = hashIp(command.ipAddress());
    if (!authThrottle.tryConsume(
        IP_THROTTLE_PREFIX + ipHash, authProperties.login().refreshIpMaxPerHour(), IP_WINDOW)) {
      authMetrics.refreshRateLimited();
      log.warn("[refresh] rate limited by ip");
      throw new AuthException(AuthErrorCode.AUTH_RATE_LIMITED);
    }

    String rawRefresh = command.refreshToken();
    if (rawRefresh == null || rawRefresh.isBlank()) {
      log.warn("[refresh] blank refresh token");
      throw new AuthException(AuthErrorCode.AUTH_REFRESH_TOKEN_INVALID);
    }
    String incomingHash = HashUtils.sha256(rawRefresh.getBytes(StandardCharsets.UTF_8));
    Instant now = Instant.now(clock);

    Optional<UUID> sidOpt = sessionRepository.resolveSessionIdByRefreshHash(incomingHash);
    if (sidOpt.isEmpty()) {
      Optional<UUID> reusedSid = sessionRepository.resolveReusedSessionIdByHash(incomingHash);
      if (reusedSid.isPresent()) {
        handleReuseCascade(reusedSid.get(), ipHash, now);
        log.warn("[refresh] token reuse detected sid={}", reusedSid.get());
        throw new AuthException(AuthErrorCode.AUTH_REFRESH_TOKEN_REUSED);
      }
      log.warn("[refresh] unknown refresh token");
      throw new AuthException(AuthErrorCode.AUTH_REFRESH_TOKEN_INVALID);
    }
    UUID sid = sidOpt.get();

    Session session =
        sessionRepository
            .findActiveById(sid, now)
            .orElseThrow(
                () -> {
                  log.warn("[refresh] session not active sid={}", sid);
                  return new AuthException(AuthErrorCode.AUTH_REFRESH_TOKEN_INVALID);
                });

    int currentCv = resolveCurrentCredentialVersion(session.getUserId());
    if (session.getCredentialVersion() != currentCv) {
      sessionRepository.revoke(sid, RevocationReason.PASSWORD_CHANGED, now);
      log.warn(
          "[refresh] credential version mismatch sid={} sessionCv={} currentCv={}",
          sid,
          session.getCredentialVersion(),
          currentCv);
      throw new AuthException(AuthErrorCode.AUTH_REFRESH_TOKEN_INVALID);
    }

    String newRawRefresh = refreshTokenGenerator.generate();
    String newHash = HashUtils.sha256(newRawRefresh.getBytes(StandardCharsets.UTF_8));
    boolean rotated =
        sessionRepository.rotateRefresh(sid, incomingHash, newHash, now, session.getExpiresAt());
    if (!rotated) {
      if (sessionRepository.resolveReusedSessionIdByHash(incomingHash).isPresent()) {
        handleReuseCascade(sid, ipHash, now);
        log.warn("[refresh] token reuse detected during rotation sid={}", sid);
        throw new AuthException(AuthErrorCode.AUTH_REFRESH_TOKEN_REUSED);
      }
      log.warn("[refresh] concurrent rotation detected sid={}", sid);
      throw new AuthException(AuthErrorCode.AUTH_REFRESH_TOKEN_INVALID);
    }

    User user =
        userRepository
            .findById(session.getUserId())
            .orElseThrow(
                () -> {
                  log.warn("[refresh] user vanished sid={} userId={}", sid, session.getUserId());
                  return new AuthException(AuthErrorCode.AUTH_REFRESH_TOKEN_INVALID);
                });
    List<String> roleCodes =
        user.getRoles() == null ? List.of() : user.getRoles().stream().map(Role::getCode).toList();

    TokenIssuer.IssuedAccessToken accessToken =
        tokenIssuer.issue(
            user.getId(), sid, currentCv, roleCodes, authorityService.permissionsFor(user), now);

    auditTrail.recordAfterCommit(
        audit(sid, OperationType.TOKEN_REFRESHED, user.getId(), now, ipHash)
            .userAgent(command.userAgent())
            .build());

    log.info("[refresh] token refreshed sid={} userId={}", sid, user.getId());

    return LoginResult.builder()
        .tokenType(TOKEN_TYPE)
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

  @Transactional
  public void logout(LogoutCommand command) {
    Instant now = Instant.now(clock);
    sessionRepository.revoke(command.sessionId(), RevocationReason.LOGOUT, now);

    auditTrail.recordAfterCommit(
        audit(
                command.sessionId(),
                OperationType.LOGOUT,
                command.userId(),
                now,
                hashIp(command.ipAddress()))
            .build());

    log.info("[logout] session revoked userId={} sid={}", command.userId(), command.sessionId());
  }

  @Transactional
  public void revoke(RevokeSessionCommand command) {
    Instant now = Instant.now(clock);
    Session session =
        sessionRepository
            .findActiveById(command.sessionId(), now)
            .orElseThrow(
                () -> {
                  log.warn("[revoke] session not found sid={}", command.sessionId());
                  return new AuthException(AuthErrorCode.AUTH_SESSION_NOT_FOUND);
                });
    if (!session.getUserId().equals(command.callerUserId())) {
      log.warn(
          "[revoke] foreign session revoke attempt sid={} callerUserId={}",
          command.sessionId(),
          command.callerUserId());
      throw new AuthException(AuthErrorCode.AUTH_SESSION_NOT_FOUND);
    }

    sessionRepository.revoke(command.sessionId(), RevocationReason.USER_TRIGGERED, now);

    auditTrail.recordAfterCommit(
        audit(
                command.sessionId(),
                OperationType.SESSION_REVOKED,
                command.callerUserId(),
                now,
                hashIp(command.ipAddress()))
            .build());

    log.info(
        "[revoke] session revoked sid={} callerUserId={}",
        command.sessionId(),
        command.callerUserId());
  }

  /**
   * Revokes every active session for {@code command.userId}, bumps the user's credential version,
   * and invalidates the credential-version cache so all outstanding access tokens fail their {@code
   * cv} check at the resource server. This is the seam any flow uses to sign a user out of every
   * device.
   */
  @Transactional
  public void revokeAll(RevokeAllSessionsCommand command) {
    User user =
        userRepository
            .findById(command.userId())
            .orElseThrow(
                () -> {
                  log.warn("[revokeAll] user not found userId={}", command.userId());
                  return new AuthException(AuthErrorCode.USER_NOT_FOUND);
                });

    Instant now = Instant.now(clock);
    user.bumpCredentialVersion(now);
    userRepository.save(user);
    credentialVersionCache.invalidate(command.userId());

    RevocationReason reason =
        command.reason() == null ? RevocationReason.USER_TRIGGERED : command.reason();
    int revoked = sessionRepository.revokeAllForUser(command.userId(), reason, now);

    auditTrail.recordAfterCommit(
        audit(
                command.userId(),
                OperationType.ALL_SESSIONS_REVOKED,
                command.userId(),
                now,
                hashIp(command.ipAddress()))
            .build());

    log.info(
        "[revokeAll] revoked all sessions userId={} count={} reason={}",
        command.userId(),
        revoked,
        reason);
  }

  /**
   * A reused refresh token means the family is compromised: revoke the session, bump the user's
   * credential version so every outstanding access token fails, and record the detection.
   */
  private void handleReuseCascade(UUID sessionId, String ipHash, Instant now) {
    authMetrics.refreshTokenReused();
    Optional<Session> maybe = sessionRepository.findById(sessionId);
    UUID userId = maybe.map(Session::getUserId).orElse(null);
    sessionRepository.revoke(sessionId, RevocationReason.TOKEN_REUSE, now);
    if (userId == null) {
      return;
    }
    userRepository
        .findById(userId)
        .ifPresent(
            user -> {
              user.bumpCredentialVersion(now);
              userRepository.save(user);
              // After commit: Redis has no rollback, so invalidating inline would drop the cached
              // version even on a path that never persisted the bump — the reload would then put
              // the old version straight back.
              AfterCommit.run(() -> credentialVersionCache.invalidate(userId));
            });

    auditTrail.recordAfterCommit(
        audit(userId, OperationType.TOKEN_REUSE_DETECTED, userId, now, ipHash).build());
  }

  private int resolveCurrentCredentialVersion(UUID userId) {
    return credentialVersionCache
        .get(userId)
        .orElseGet(
            () -> {
              int cv =
                  userRepository
                      .findById(userId)
                      .map(User::getCredentialVersion)
                      .orElseThrow(
                          () -> new AuthException(AuthErrorCode.AUTH_REFRESH_TOKEN_INVALID));
              credentialVersionCache.put(userId, cv);
              return cv;
            });
  }

  private static String hashIp(String ip) {
    return ip == null ? null : HashUtils.sha256(ip.getBytes(StandardCharsets.UTF_8));
  }

  private static AuditLog.AuditLogBuilder<?, ?> audit(
      UUID objectId, OperationType operation, UUID actorId, Instant now, String ipHash) {
    return AuditTrail.entry(AuditLogDomain.AUTH, objectId, operation, actorId, now)
        .ipAddress(ipHash);
  }
}
