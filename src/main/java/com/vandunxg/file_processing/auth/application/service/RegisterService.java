package com.vandunxg.file_processing.auth.application.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import com.vandunxg.common.utils.HashUtils;
import com.vandunxg.common.utils.IdUtils;
import com.vandunxg.common.utils.StrUtils;
import com.vandunxg.file_processing.auth.application.command.RegisterCommand;
import com.vandunxg.file_processing.auth.application.port.in.RegisterUseCase;
import com.vandunxg.file_processing.auth.application.port.out.AuditLogPort;
import com.vandunxg.file_processing.auth.application.port.out.EmailSenderPort;
import com.vandunxg.file_processing.auth.application.port.out.EmailVerificationTokenRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.PasswordHasherPort;
import com.vandunxg.file_processing.auth.application.port.out.RegisterThrottlePort;
import com.vandunxg.file_processing.auth.application.port.out.RoleRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.UserRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.UserRoleRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.VerificationTokenGeneratorPort;
import com.vandunxg.file_processing.auth.application.result.RegisterResult;
import com.vandunxg.file_processing.auth.configuration.AuthProperties;
import com.vandunxg.file_processing.auth.domain.exception.AuthDomainException;
import com.vandunxg.file_processing.auth.domain.exception.AuthErrorCode;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import com.vandunxg.file_processing.auth.domain.model.AuditLogDomain;
import com.vandunxg.file_processing.auth.domain.model.EmailVerificationToken;
import com.vandunxg.file_processing.auth.domain.model.OperationType;
import com.vandunxg.file_processing.auth.domain.model.Role;
import com.vandunxg.file_processing.auth.domain.model.User;
import com.vandunxg.file_processing.auth.domain.model.UserRole;
import com.vandunxg.file_processing.auth.domain.policy.PasswordPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-REGISTER")
public class RegisterService implements RegisterUseCase {

  private static final String OPERATOR_ROLE_CODE = "OPERATOR";
  private static final String THROTTLE_KEY_PREFIX = "register:";

  private final RegisterThrottlePort throttlePort;
  private final UserRepositoryPort userRepositoryPort;
  private final RoleRepositoryPort roleRepositoryPort;
  private final UserRoleRepositoryPort userRoleRepositoryPort;
  private final AuditLogPort auditLogPort;
  private final EmailVerificationTokenRepositoryPort tokenRepositoryPort;
  private final PasswordHasherPort passwordHasherPort;
  private final VerificationTokenGeneratorPort tokenGeneratorPort;
  private final EmailSenderPort emailSenderPort;
  private final AuthProperties authProperties;
  private final Clock clock;

  private final PasswordPolicy passwordPolicy = new PasswordPolicy();

  @Override
  @Transactional
  public RegisterResult register(RegisterCommand command) {
    if (!throttlePort.tryConsume(
        THROTTLE_KEY_PREFIX + command.getIpAddress(),
        authProperties.register().maxAttemptsPerHour())) {
      log.warn(
          "[register] rate limited maxAttemptsPerHour={}",
          authProperties.register().maxAttemptsPerHour());
      throw new AuthDomainException(AuthErrorCode.AUTH_RATE_LIMITED);
    }

    String normalizedUsername = User.normalize(command.getUsername());
    String normalizedEmail = User.normalize(command.getEmail());

    PasswordPolicy.ValidationResult validation =
        passwordPolicy.validate(command.getPassword(), normalizedUsername, normalizedEmail);
    if (!validation.valid()) {
      log.warn(
          "[register] password policy violation username={} reason={}",
          normalizedUsername,
          validation.reason());
      throw new AuthDomainException(AuthErrorCode.PASSWORD_POLICY_VIOLATION);
    }

    if (userRepositoryPort.existsByNormalizedUsername(normalizedUsername)) {
      log.warn("[register] duplicate username username={}", normalizedUsername);
      throw new AuthDomainException(AuthErrorCode.USERNAME_ALREADY_EXISTS);
    }
    if (userRepositoryPort.existsByNormalizedEmail(normalizedEmail)) {
      log.warn("[register] duplicate email email={}", StrUtils.emailFormat(normalizedEmail));
      throw new AuthDomainException(AuthErrorCode.EMAIL_ALREADY_EXISTS);
    }

    Role operatorRole =
        roleRepositoryPort
            .findByCode(OPERATOR_ROLE_CODE)
            .orElseThrow(
                () -> {
                  log.warn("[register] operator role not found code={}", OPERATOR_ROLE_CODE);
                  return new AuthDomainException(AuthErrorCode.INVALID_ROLE);
                });

    Instant now = Instant.now(clock);
    String passwordHash = passwordHasherPort.hash(command.getPassword());
    User user =
        User.register(
            command.getUsername(),
            command.getEmail(),
            command.getDisplayName(),
            passwordHash,
            operatorRole,
            now);

    User saved;
    try {
      saved = userRepositoryPort.save(user);
    } catch (DataIntegrityViolationException e) {
      String cause =
          Optional.ofNullable(e.getMostSpecificCause()).map(Throwable::getMessage).orElse("");
      if (cause.contains("auth_users_normalized_email_uk")) {
        log.warn(
            "[register] concurrent duplicate email detected on save email={}",
            StrUtils.emailFormat(normalizedEmail));
        throw new AuthDomainException(AuthErrorCode.EMAIL_ALREADY_EXISTS);
      }
      log.warn(
          "[register] concurrent duplicate username detected on save username={}",
          normalizedUsername);
      throw new AuthDomainException(AuthErrorCode.USERNAME_ALREADY_EXISTS);
    }

    userRoleRepositoryPort.save(new UserRole(saved.getId(), operatorRole.getId()));

    String rawToken = tokenGeneratorPort.generate();
    String tokenHash = HashUtils.sha256(rawToken.getBytes(StandardCharsets.UTF_8));
    String ipHash =
        command.getIpAddress() == null
            ? null
            : HashUtils.sha256(command.getIpAddress().getBytes(StandardCharsets.UTF_8));

    EmailVerificationToken token =
        EmailVerificationToken.issue(
            IdUtils.nextId(),
            saved.getId(),
            tokenHash,
            now,
            authProperties.emailVerification().tokenTtl(),
            ipHash);
    tokenRepositoryPort.save(token);

    auditLogPort.record(
        AuditLog.builder()
            .id(IdUtils.nextId())
            .domain(AuditLogDomain.AUTH)
            .objectId(saved.getId())
            .operation(OperationType.USER_REGISTERED)
            .changedBy(saved.getId())
            .changedAt(now)
            .ipAddress(ipHash)
            .build());

    log.info("[register] registered user userId={} status={}", saved.getId(), saved.getStatus());

    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      String verificationLink = authProperties.emailVerification().baseUrl() + "?token=" + rawToken;
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              try {
                emailSenderPort.sendVerificationEmail(
                    saved.getEmail(), saved.getDisplayName(), verificationLink);
              } catch (Exception e) {
                log.warn(
                    "[register] failed to send verification email after commit userId={}",
                    saved.getId(),
                    e);
              }
            }
          });
    }

    return RegisterResult.from(saved);
  }
}
