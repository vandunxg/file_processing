package com.vandunxg.file_processing.auth.application.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;

import com.vandunxg.common.utils.HashUtils;
import com.vandunxg.common.utils.IdUtils;
import com.vandunxg.file_processing.auth.adapter.out.metrics.AuthMetrics;
import com.vandunxg.file_processing.auth.application.command.ResetPasswordCommand;
import com.vandunxg.file_processing.auth.application.port.in.ResetPasswordUseCase;
import com.vandunxg.file_processing.auth.application.port.out.AuditLogEventPublisherPort;
import com.vandunxg.file_processing.auth.application.port.out.CredentialVersionCachePort;
import com.vandunxg.file_processing.auth.application.port.out.PasswordHasherPort;
import com.vandunxg.file_processing.auth.application.port.out.PasswordResetTokenRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.SessionRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.UserRepositoryPort;
import com.vandunxg.file_processing.auth.domain.exception.AuthDomainException;
import com.vandunxg.file_processing.auth.domain.exception.AuthErrorCode;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import com.vandunxg.file_processing.auth.domain.model.AuditLogDomain;
import com.vandunxg.file_processing.auth.domain.model.OperationType;
import com.vandunxg.file_processing.auth.domain.model.PasswordResetToken;
import com.vandunxg.file_processing.auth.domain.model.RevocationReason;
import com.vandunxg.file_processing.auth.domain.model.User;
import com.vandunxg.file_processing.auth.domain.policy.PasswordPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-RESET-PASSWORD")
public class ResetPasswordService implements ResetPasswordUseCase {

  private final PasswordResetTokenRepositoryPort tokenRepositoryPort;
  private final UserRepositoryPort userRepositoryPort;
  private final PasswordHasherPort passwordHasherPort;
  private final SessionRepositoryPort sessionRepositoryPort;
  private final CredentialVersionCachePort credentialVersionCachePort;
  private final AuditLogEventPublisherPort auditLogEventPublisherPort;
  private final AuthMetrics authMetrics;
  private final Clock clock;
  private final PasswordPolicy passwordPolicy = new PasswordPolicy();

  @Override
  @Transactional
  public void reset(ResetPasswordCommand command) {
    PasswordResetToken token =
        tokenRepositoryPort
            .findByTokenHashForUpdate(hash(command.getToken()))
            .orElseThrow(() -> rejected(AuthErrorCode.PASSWORD_RESET_TOKEN_INVALID));
    Instant now = Instant.now(clock);
    if (!token.isUsableAt(now)) {
      throw rejected(AuthErrorCode.PASSWORD_RESET_TOKEN_INVALID);
    }

    User user =
        userRepositoryPort
            .findById(token.getUserId())
            .orElseThrow(() -> rejected(AuthErrorCode.PASSWORD_RESET_TOKEN_INVALID));
    if (!user.isActive() && !user.isPendingVerify()) {
      throw rejected(AuthErrorCode.PASSWORD_RESET_TOKEN_INVALID);
    }
    if (!Objects.equals(command.getNewPassword(), command.getConfirmPassword())) {
      throw rejected(AuthErrorCode.PASSWORD_CONFIRMATION_MISMATCH);
    }
    if (!passwordPolicy
        .validate(command.getNewPassword(), user.getNormalizedUsername(), user.getNormalizedEmail())
        .valid()) {
      throw rejected(AuthErrorCode.PASSWORD_POLICY_VIOLATION);
    }
    if (passwordHasherPort.matches(command.getNewPassword(), user.getPasswordHash())) {
      throw rejected(AuthErrorCode.PASSWORD_SAME_AS_CURRENT);
    }

    token.consume(now);
    tokenRepositoryPort.save(token);
    user.resetPassword(passwordHasherPort.hash(command.getNewPassword()), now);
    userRepositoryPort.save(user);
    sessionRepositoryPort.revokeAllForUser(user.getId(), RevocationReason.PASSWORD_CHANGED, now);
    credentialVersionCachePort.invalidate(user.getId());
    authMetrics.passwordResetCompleted();

    AuditLog audit =
        AuditLog.builder()
            .id(IdUtils.nextId())
            .domain(AuditLogDomain.AUTH)
            .objectId(user.getId())
            .operation(OperationType.PASSWORD_RESET_COMPLETED)
            .changedBy(user.getId())
            .changedAt(now)
            .ipAddress(hash(command.getIpAddress()))
            .build();
    publishAfterCommit(audit);
  }

  private void publishAfterCommit(AuditLog audit) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            try {
              auditLogEventPublisherPort.publish(audit);
            } catch (Exception e) {
              log.warn("[reset] failed to publish password reset audit", e);
            }
          }
        });
  }

  private static String hash(String value) {
    return value == null ? null : HashUtils.sha256(value.getBytes(StandardCharsets.UTF_8));
  }

  private AuthDomainException rejected(AuthErrorCode errorCode) {
    authMetrics.passwordResetRejected();
    return new AuthDomainException(errorCode);
  }
}
