package com.vandunxg.file_processing.auth.application.service;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;

import com.vandunxg.common.utils.IdUtils;
import com.vandunxg.file_processing.auth.application.port.in.BootstrapAdminUseCase;
import com.vandunxg.file_processing.auth.application.port.out.AuditLogEventPublisherPort;
import com.vandunxg.file_processing.auth.application.port.out.BootstrapAdminLockPort;
import com.vandunxg.file_processing.auth.application.port.out.PasswordHasherPort;
import com.vandunxg.file_processing.auth.application.port.out.RoleRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.UserRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.UserRoleRepositoryPort;
import com.vandunxg.file_processing.auth.configuration.AuthProperties;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import com.vandunxg.file_processing.auth.domain.model.AuditLogDomain;
import com.vandunxg.file_processing.auth.domain.model.OperationType;
import com.vandunxg.file_processing.auth.domain.model.Role;
import com.vandunxg.file_processing.auth.domain.model.User;
import com.vandunxg.file_processing.auth.domain.model.UserRole;
import com.vandunxg.file_processing.auth.domain.policy.PasswordPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-BOOTSTRAP")
public class BootstrapAdminService implements BootstrapAdminUseCase {

  private static final String ADMIN_ROLE_CODE = "ADMIN";

  private final BootstrapAdminLockPort bootstrapAdminLockPort;
  private final UserRepositoryPort userRepositoryPort;
  private final RoleRepositoryPort roleRepositoryPort;
  private final UserRoleRepositoryPort userRoleRepositoryPort;
  private final PasswordHasherPort passwordHasherPort;
  private final AuditLogEventPublisherPort auditLogEventPublisherPort;
  private final AuthProperties authProperties;
  private final Clock clock;

  private final PasswordPolicy passwordPolicy = new PasswordPolicy();

  @Override
  @Transactional
  public void bootstrap() {
    AuthProperties.Bootstrap.Admin admin = bootstrapAdmin();
    if (!admin.enabled()) {
      return;
    }

    bootstrapAdminLockPort.acquire();
    if (userRepositoryPort.existsAny()) {
      log.info("[bootstrap] skipped because a user already exists");
      return;
    }
    validate(admin);

    Role adminRole =
        roleRepositoryPort
            .findByCode(ADMIN_ROLE_CODE)
            .orElseThrow(() -> new IllegalStateException("Bootstrap admin role is unavailable"));
    Instant now = Instant.now(clock);
    User user =
        User.bootstrapAdmin(
            admin.username(),
            admin.email(),
            admin.displayName(),
            passwordHasherPort.hash(admin.password()),
            adminRole,
            now);
    User saved = userRepositoryPort.save(user);
    userRoleRepositoryPort.save(new UserRole(saved.getId(), adminRole.getId()));

    publishAfterCommit(
        AuditLog.builder()
            .id(IdUtils.nextId())
            .domain(AuditLogDomain.AUTH)
            .objectId(saved.getId())
            .operation(OperationType.ADMIN_BOOTSTRAPPED)
            .changedAt(now)
            .build());
    log.info("[bootstrap] admin created userId={}", saved.getId());
  }

  private AuthProperties.Bootstrap.Admin bootstrapAdmin() {
    if (authProperties.bootstrap() == null || authProperties.bootstrap().admin() == null) {
      throw new IllegalStateException("Invalid bootstrap admin configuration");
    }
    return authProperties.bootstrap().admin();
  }

  private void validate(AuthProperties.Bootstrap.Admin admin) {
    String username = normalize(admin.username());
    String email = normalize(admin.email());
    String displayName = normalizeDisplayName(admin.displayName());
    if (username.length() < 3
        || username.length() > 64
        || !username.matches("[A-Za-z0-9._-]+")
        || email.length() > 254
        || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
        || displayName.length() < 2
        || displayName.length() > 150
        || !passwordPolicy.validate(admin.password(), User.normalize(username), User.normalize(email)).valid()) {
      throw new IllegalStateException("Invalid bootstrap admin configuration");
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

  private static String normalize(String value) {
    return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC).trim();
  }

  private static String normalizeDisplayName(String value) {
    return normalize(value).replaceAll("\\s+", " ");
  }
}
