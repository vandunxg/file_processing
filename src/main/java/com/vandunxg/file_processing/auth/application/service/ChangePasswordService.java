package com.vandunxg.file_processing.auth.application.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.vandunxg.common.utils.HashUtils;
import com.vandunxg.common.utils.IdUtils;
import com.vandunxg.file_processing.auth.application.command.ChangePasswordCommand;
import com.vandunxg.file_processing.auth.application.port.in.ChangePasswordUseCase;
import com.vandunxg.file_processing.auth.application.port.out.AuditLogEventPublisherPort;
import com.vandunxg.file_processing.auth.application.port.out.CredentialVersionCachePort;
import com.vandunxg.file_processing.auth.application.port.out.PasswordHasherPort;
import com.vandunxg.file_processing.auth.application.port.out.SessionRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.UserRepositoryPort;
import com.vandunxg.file_processing.auth.domain.exception.AuthDomainException;
import com.vandunxg.file_processing.auth.domain.exception.AuthErrorCode;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import com.vandunxg.file_processing.auth.domain.model.AuditLogDomain;
import com.vandunxg.file_processing.auth.domain.model.OperationType;
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
@Slf4j(topic = "AUTH-CHANGE-PASSWORD")
public class ChangePasswordService implements ChangePasswordUseCase {

  private final UserRepositoryPort userRepositoryPort;
  private final PasswordHasherPort passwordHasherPort;
  private final SessionRepositoryPort sessionRepositoryPort;
  private final CredentialVersionCachePort credentialVersionCachePort;
  private final AuditLogEventPublisherPort auditLogEventPublisherPort;
  private final Clock clock;
  private final PasswordPolicy passwordPolicy = new PasswordPolicy();

  @Override
  @Transactional
  public void change(ChangePasswordCommand command) {
    change(command, false);
  }

  @Override
  @Transactional
  public void complete(ChangePasswordCommand command) {
    change(command, true);
  }

  private void change(ChangePasswordCommand command, boolean mustChangePassword) {
    User user =
        userRepositoryPort
            .findById(command.getUserId())
            .orElseThrow(() -> new AuthDomainException(AuthErrorCode.USER_NOT_FOUND));
    if (mustChangePassword && (!user.isActive() || !user.isMustChangePassword())) {
      throw new AuthDomainException(AuthErrorCode.PASSWORD_CHANGE_TOKEN_INVALID);
    }
    if (!passwordHasherPort.matches(command.getCurrentPassword(), user.getPasswordHash())) {
      throw new AuthDomainException(AuthErrorCode.CURRENT_PASSWORD_INVALID);
    }
    if (!Objects.equals(command.getNewPassword(), command.getConfirmPassword())) {
      throw new AuthDomainException(AuthErrorCode.PASSWORD_CONFIRMATION_MISMATCH);
    }
    if (!passwordPolicy
        .validate(command.getNewPassword(), user.getNormalizedUsername(), user.getNormalizedEmail())
        .valid()) {
      throw new AuthDomainException(AuthErrorCode.PASSWORD_POLICY_VIOLATION);
    }
    if (passwordHasherPort.matches(command.getNewPassword(), user.getPasswordHash())) {
      throw new AuthDomainException(AuthErrorCode.PASSWORD_REUSE_NOT_ALLOWED);
    }

    Instant now = Instant.now(clock);
    user.changePassword(passwordHasherPort.hash(command.getNewPassword()), now);
    userRepositoryPort.save(user);
    sessionRepositoryPort.revokeAllForUser(user.getId(), RevocationReason.PASSWORD_CHANGED, now);

    AuditLog audit =
        AuditLog.builder()
            .id(IdUtils.nextId())
            .domain(AuditLogDomain.AUTH)
            .objectId(user.getId())
            .operation(OperationType.PASSWORD_CHANGED)
            .changedBy(user.getId())
            .changedAt(now)
            .ipAddress(hash(command.getIpAddress()))
            .build();
    invalidateCacheAndPublishAuditAfterCommit(user.getId(), audit);
  }

  private void invalidateCacheAndPublishAuditAfterCommit(UUID userId, AuditLog audit) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            try {
              credentialVersionCachePort.invalidate(userId);
            } catch (Exception e) {
              log.warn("[change] failed to invalidate credential cache userId={}", userId, e);
            }
            try {
              auditLogEventPublisherPort.publish(audit);
            } catch (Exception e) {
              log.warn("[change] failed to publish password change audit userId={}", userId, e);
            }
          }
        });
  }

  private static String hash(String value) {
    return value == null ? null : HashUtils.sha256(value.getBytes(StandardCharsets.UTF_8));
  }
}
