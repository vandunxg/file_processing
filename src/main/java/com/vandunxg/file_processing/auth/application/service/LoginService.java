package com.vandunxg.file_processing.auth.application.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.vandunxg.common.utils.HashUtils;
import com.vandunxg.common.utils.IdUtils;
import com.vandunxg.file_processing.auth.application.command.LoginCommand;
import com.vandunxg.file_processing.auth.application.port.in.LoginUseCase;
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
@Slf4j(topic = "AUTH-LOGIN")
public class LoginService implements LoginUseCase {

  private static final Duration IP_WINDOW = Duration.ofHours(1);
  private static final String IP_THROTTLE_PREFIX = "login:ip:";
  private static final String USER_THROTTLE_PREFIX = "login:user:";
  private static final String TOKEN_TYPE = "Bearer";

  private final AuthThrottlePort throttlePort;
  private final UserRepositoryPort userRepositoryPort;
  private final PasswordHasherPort passwordHasherPort;
  private final RefreshTokenGeneratorPort refreshTokenGeneratorPort;
  private final SessionRepositoryPort sessionRepositoryPort;
  private final JwtIssuerPort jwtIssuerPort;
  private final CredentialVersionCachePort credentialVersionCachePort;
  private final AuditLogEventPublisherPort auditLogEventPublisherPort;
  private final AuthProperties authProperties;
  private final Clock clock;

  @Override
  @Transactional
  public LoginResult login(LoginCommand command) {
    String ipHash = hashIp(command.getIpAddress());
    String normalizedUsername = User.normalize(command.getUsername());

    if (!throttlePort.tryConsume(
        IP_THROTTLE_PREFIX + ipHash, authProperties.login().ipMaxPerHour(), IP_WINDOW)) {
      log.warn("[login] rate limited by ip");
      throw new AuthDomainException(AuthErrorCode.AUTH_RATE_LIMITED);
    }
    if (!throttlePort.tryConsume(
        USER_THROTTLE_PREFIX + normalizedUsername,
        authProperties.login().usernameMaxPerWindow(),
        authProperties.login().usernameWindow())) {
      log.warn("[login] rate limited by username");
      throw new AuthDomainException(AuthErrorCode.AUTH_RATE_LIMITED);
    }

    User user =
        userRepositoryPort
            .findByNormalizedIdentifier(normalizedUsername)
            .orElseThrow(
                () -> {
                  log.warn("[login] user not found username={}", normalizedUsername);
                  return new AuthDomainException(AuthErrorCode.INVALID_CREDENTIALS);
                });

    Instant now = Instant.now(clock);

    if (user.isLocked(now)) {
      log.warn("[login] account locked userId={}", user.getId());
      throw new AuthDomainException(AuthErrorCode.ACCOUNT_LOCKED);
    }
    if (!passwordHasherPort.matches(command.getPassword(), user.getPasswordHash())) {
      recordFailedLogin(user, now, ipHash);
      log.warn("[login] invalid password userId={}", user.getId());
      throw new AuthDomainException(AuthErrorCode.INVALID_CREDENTIALS);
    }
    if (user.isPendingVerify()) {
      log.warn("[login] account not verified userId={}", user.getId());
      throw new AuthDomainException(AuthErrorCode.EMAIL_VERIFICATION_REQUIRED);
    }
    if (!user.isActive()) {
      log.warn("[login] account not active userId={} status={}", user.getId(), user.getStatus());
      throw new AuthDomainException(AuthErrorCode.INVALID_CREDENTIALS);
    }

    user.resetFailedLogin();
    User saved = userRepositoryPort.save(user);
    AuditLog auditLog =
        AuditLog.builder()
            .id(IdUtils.nextId())
            .domain(AuditLogDomain.AUTH)
            .objectId(saved.getId())
            .operation(OperationType.LOGIN_SUCCEEDED)
            .changedBy(saved.getId())
            .changedAt(now)
            .ipAddress(ipHash)
            .userAgent(command.getUserAgent())
            .build();

    if (saved.isMustChangePassword()) {
      JwtIssuerPort.IssuedPasswordChangeToken passwordChangeToken =
          jwtIssuerPort.issuePasswordChange(saved.getId(), saved.getCredentialVersion(), now);
      credentialVersionCachePort.put(saved.getId(), saved.getCredentialVersion());
      publishAfterCommit(auditLog);
      return LoginResult.builder()
          .status("PASSWORD_CHANGE_REQUIRED")
          .passwordChangeToken(passwordChangeToken.token())
          .expiresIn(
              Duration.between(passwordChangeToken.issuedAt(), passwordChangeToken.expiresAt())
                  .toSeconds())
          .build();
    }

    String rawRefresh = refreshTokenGeneratorPort.generate();
    String refreshHash = HashUtils.sha256(rawRefresh.getBytes(StandardCharsets.UTF_8));

    Session session =
        Session.issue(
            IdUtils.nextId(),
            saved.getId(),
            saved.getCredentialVersion(),
            command.getUserAgent(),
            ipHash,
            now,
            authProperties.refresh().tokenTtl());
    sessionRepositoryPort.save(session, refreshHash);
    credentialVersionCachePort.put(saved.getId(), saved.getCredentialVersion());

    List<String> roleCodes = saved.getRoles().stream().map(Role::getCode).toList();
    JwtIssuerPort.IssuedAccessToken accessToken =
        jwtIssuerPort.issue(
            saved.getId(), session.getId(), saved.getCredentialVersion(), roleCodes, now);

    publishAfterCommit(auditLog);

    log.info("[login] login succeeded userId={} sid={}", saved.getId(), session.getId());

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

  private void recordFailedLogin(User user, Instant now, String ipHash) {
    boolean wasLocked = user.getLockedUntil() != null && user.isLocked(now);
    user.registerFailedLogin(
        now, authProperties.login().maxFailures(), authProperties.login().lockDuration());
    userRepositoryPort.save(user);

    AuditLog failedAudit =
        AuditLog.builder()
            .id(IdUtils.nextId())
            .domain(AuditLogDomain.AUTH)
            .objectId(user.getId())
            .operation(OperationType.LOGIN_FAILED)
            .changedBy(user.getId())
            .changedAt(now)
            .ipAddress(ipHash)
            .build();
    publishAfterCommit(failedAudit);

    boolean nowLocked = user.getLockedUntil() != null && user.isLocked(now);
    if (nowLocked && !wasLocked) {
      AuditLog lockAudit =
          AuditLog.builder()
              .id(IdUtils.nextId())
              .domain(AuditLogDomain.AUTH)
              .objectId(user.getId())
              .operation(OperationType.ACCOUNT_LOCKED_OUT)
              .changedBy(user.getId())
              .changedAt(now)
              .ipAddress(ipHash)
              .build();
      publishAfterCommit(lockAudit);
    }
  }

  private void publishAfterCommit(AuditLog auditLog) {
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
                  "[publishAfterCommit] failed to publish audit event operation={}",
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
