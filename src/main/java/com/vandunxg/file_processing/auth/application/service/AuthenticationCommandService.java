package com.vandunxg.file_processing.auth.application.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.vandunxg.common.utils.HashUtils;
import com.vandunxg.common.utils.IdUtils;
import com.vandunxg.file_processing.auth.application.AuditTrail;
import com.vandunxg.file_processing.auth.application.AuthProperties;
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
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import com.vandunxg.file_processing.auth.domain.model.AuditLogDomain;
import com.vandunxg.file_processing.auth.domain.model.OperationType;
import com.vandunxg.file_processing.auth.domain.model.Role;
import com.vandunxg.file_processing.auth.domain.model.Session;
import com.vandunxg.file_processing.auth.domain.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Credential verification and first token issuance. A successful login either opens a refresh
 * session or, when the account still carries a temporary password, returns only a password-change
 * token so no usable access token is issued before the password is rotated.
 */
@Service
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-LOGIN")
public class AuthenticationCommandService {

  private static final Duration IP_WINDOW = Duration.ofHours(1);
  private static final String IP_THROTTLE_PREFIX = "login:ip:";
  private static final String USER_THROTTLE_PREFIX = "login:user:";
  private static final String TOKEN_TYPE = "Bearer";

  private final AuthThrottle authThrottle;
  private final UserRepository userRepository;
  private final PasswordHasher passwordHasher;
  private final RefreshTokenGenerator refreshTokenGenerator;
  private final SessionRepository sessionRepository;
  private final TokenIssuer tokenIssuer;
  private final AuthorityService authorityService;
  private final CredentialVersionCache credentialVersionCache;
  private final AuditTrail auditTrail;
  private final AuthMetrics authMetrics;
  private final AuthProperties authProperties;
  private final Clock clock;

  /**
   * A rejected login still has to persist what it learned. {@link AuthException} is unchecked, so
   * the default rollback rule would discard the failed-login counter written by {@link
   * #recordFailedLogin} — and with it the lock-out that protects against brute force — the moment
   * the credentials are refused. Every write on a rejecting path here is one we want kept, so
   * failures of this type commit instead of rolling back. Keeping the commit also lets the
   * after-commit audit events (LOGIN_FAILED, ACCOUNT_LOCKED_OUT) fire at all.
   */
  @Transactional(noRollbackFor = AuthException.class)
  public LoginResult login(LoginCommand command) {
    String ipHash = hashIp(command.ipAddress());
    String normalizedUsername = User.normalize(command.username());

    if (!authThrottle.tryConsume(
        IP_THROTTLE_PREFIX + ipHash, authProperties.login().ipMaxPerHour(), IP_WINDOW)) {
      authMetrics.loginRateLimited();
      log.warn("[login] rate limited by ip");
      throw new AuthException(AuthErrorCode.AUTH_RATE_LIMITED);
    }
    if (!authThrottle.tryConsume(
        USER_THROTTLE_PREFIX + normalizedUsername,
        authProperties.login().usernameMaxPerWindow(),
        authProperties.login().usernameWindow())) {
      authMetrics.loginRateLimited();
      log.warn("[login] rate limited by username");
      throw new AuthException(AuthErrorCode.AUTH_RATE_LIMITED);
    }

    User user =
        userRepository
            .findByNormalizedIdentifier(normalizedUsername)
            .orElseThrow(
                () -> {
                  authMetrics.loginInvalidCredentials();
                  log.warn("[login] user not found username={}", normalizedUsername);
                  return new AuthException(AuthErrorCode.AUTH_INVALID_CREDENTIALS);
                });

    Instant now = Instant.now(clock);

    if (user.isLocked(now)) {
      authMetrics.loginLocked();
      log.warn("[login] account locked userId={}", user.getId());
      throw new AuthException(AuthErrorCode.AUTH_ACCOUNT_LOCKED);
    }
    if (!passwordHasher.matches(command.password(), user.getPasswordHash())) {
      recordFailedLogin(user, now, ipHash);
      authMetrics.loginInvalidCredentials();
      log.warn("[login] invalid password userId={}", user.getId());
      throw new AuthException(AuthErrorCode.AUTH_INVALID_CREDENTIALS);
    }
    if (user.isPendingVerify()) {
      authMetrics.loginPendingVerification();
      log.warn("[login] account not verified userId={}", user.getId());
      throw new AuthException(AuthErrorCode.AUTH_EMAIL_VERIFICATION_REQUIRED);
    }
    if (!user.isActive()) {
      authMetrics.loginDisabled();
      log.warn("[login] account not active userId={} status={}", user.getId(), user.getStatus());
      throw new AuthException(AuthErrorCode.AUTH_INVALID_CREDENTIALS);
    }

    user.resetFailedLogin();
    User saved = userRepository.save(user);
    AuditLog auditLog =
        audit(saved.getId(), OperationType.LOGIN_SUCCEEDED, now, ipHash)
            .userAgent(command.userAgent())
            .build();

    if (saved.isMustChangePassword()) {
      TokenIssuer.IssuedPasswordChangeToken passwordChangeToken =
          tokenIssuer.issuePasswordChange(saved.getId(), saved.getCredentialVersion(), now);
      credentialVersionCache.put(saved.getId(), saved.getCredentialVersion());
      auditTrail.recordAfterCommit(auditLog);
      authMetrics.loginSucceeded();
      return LoginResult.builder()
          .status("PASSWORD_CHANGE_REQUIRED")
          .passwordChangeToken(passwordChangeToken.token())
          .expiresIn(
              Duration.between(passwordChangeToken.issuedAt(), passwordChangeToken.expiresAt())
                  .toSeconds())
          .build();
    }

    String rawRefresh = refreshTokenGenerator.generate();
    String refreshHash = HashUtils.sha256(rawRefresh.getBytes(StandardCharsets.UTF_8));

    Session session =
        Session.issue(
            IdUtils.nextId(),
            saved.getId(),
            saved.getCredentialVersion(),
            command.userAgent(),
            ipHash,
            now,
            authProperties.refresh().tokenTtl());
    sessionRepository.save(session, refreshHash);
    credentialVersionCache.put(saved.getId(), saved.getCredentialVersion());

    List<String> roleCodes = saved.getRoles().stream().map(Role::getCode).toList();
    List<String> permissions = authorityService.permissionsFor(saved);
    TokenIssuer.IssuedAccessToken accessToken =
        tokenIssuer.issue(
            saved.getId(),
            session.getId(),
            saved.getCredentialVersion(),
            roleCodes,
            permissions,
            now);

    auditTrail.recordAfterCommit(auditLog);

    log.info("[login] login succeeded userId={} sid={}", saved.getId(), session.getId());
    authMetrics.loginSucceeded();

    return LoginResult.builder()
        .tokenType(TOKEN_TYPE)
        .accessToken(accessToken.token())
        .expiresIn(Duration.between(accessToken.issuedAt(), accessToken.expiresAt()).toSeconds())
        .accessTokenExpiresAt(accessToken.expiresAt())
        .refreshToken(rawRefresh)
        .refreshExpiresIn(Duration.between(now, session.getExpiresAt()).toSeconds())
        .refreshTokenExpiresAt(session.getExpiresAt())
        .sessionId(session.getId())
        .userId(saved.getId())
        .build();
  }

  /** Counts the failure and, when it crosses the threshold, records the resulting lock-out once. */
  private void recordFailedLogin(User user, Instant now, String ipHash) {
    boolean wasLocked = user.getLockedUntil() != null && user.isLocked(now);
    user.registerFailedLogin(
        now, authProperties.login().maxFailures(), authProperties.login().lockDuration());
    userRepository.save(user);

    auditTrail.recordAfterCommit(
        audit(user.getId(), OperationType.LOGIN_FAILED, now, ipHash).build());

    boolean nowLocked = user.getLockedUntil() != null && user.isLocked(now);
    if (nowLocked && !wasLocked) {
      auditTrail.recordAfterCommit(
          audit(user.getId(), OperationType.ACCOUNT_LOCKED_OUT, now, ipHash).build());
    }
  }

  private static String hashIp(String ip) {
    return ip == null ? null : HashUtils.sha256(ip.getBytes(StandardCharsets.UTF_8));
  }

  private static AuditLog.AuditLogBuilder<?, ?> audit(
      UUID userId, OperationType operation, Instant now, String ipHash) {
    return AuditTrail.entry(AuditLogDomain.AUTH, userId, operation, userId, now).ipAddress(ipHash);
  }
}
