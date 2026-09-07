package com.vandunxg.file_processing.auth.application.service;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;

import com.vandunxg.common.utils.IdUtils;
import com.vandunxg.file_processing.auth.application.AuditTrail;
import com.vandunxg.file_processing.auth.application.AuthProperties;
import com.vandunxg.file_processing.auth.application.capability.BootstrapAdminLock;
import com.vandunxg.file_processing.auth.application.capability.PasswordHasher;
import com.vandunxg.file_processing.auth.application.exception.AuthException;
import com.vandunxg.file_processing.auth.domain.RoleRepository;
import com.vandunxg.file_processing.auth.domain.UserRepository;
import com.vandunxg.file_processing.auth.domain.exception.AuthRuleViolation;
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

@Service
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-BOOTSTRAP")
public class AdminBootstrapService {

  private static final String ADMIN_ROLE_CODE = "ADMIN";

  private final BootstrapAdminLock bootstrapAdminLock;
  private final UserRepository userRepository;
  private final RoleRepository roleRepository;
  private final PasswordHasher passwordHasher;
  private final AuditTrail auditTrail;
  private final AuthProperties authProperties;
  private final Clock clock;

  private final PasswordPolicy passwordPolicy = new PasswordPolicy();

  @Transactional
  public void bootstrap() {
    AuthProperties.Bootstrap.Admin admin = bootstrapAdmin();
    if (!admin.enabled()) {
      return;
    }

    bootstrapAdminLock.acquire();
    if (userRepository.existsAny()) {
      log.info("[bootstrap] skipped because a user already exists");
      return;
    }
    validate(admin);

    Role adminRole =
        roleRepository
            .findByCode(ADMIN_ROLE_CODE)
            .orElseThrow(() -> new IllegalStateException("Bootstrap admin role is unavailable"));
    Instant now = Instant.now(clock);
    User user;
    try {
      user =
          User.bootstrapAdmin(
              admin.username(),
              admin.email(),
              admin.displayName(),
              passwordHasher.hash(admin.password()),
              adminRole,
              now);
    } catch (AuthRuleViolation violation) {
      throw AuthException.of(violation);
    }
    User saved = userRepository.save(user);
    userRepository.assignRole(new UserRole(saved.getId(), adminRole.getId()));

    auditTrail.recordAfterCommit(
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
        || !passwordPolicy
            .validate(admin.password(), User.normalize(username), User.normalize(email))
            .valid()) {
      throw new IllegalStateException("Invalid bootstrap admin configuration");
    }
  }

  private static String normalize(String value) {
    return value == null ? "" : Normalizer.normalize(value, Normalizer.Form.NFKC).trim();
  }

  private static String normalizeDisplayName(String value) {
    return normalize(value).replaceAll("\\s+", " ");
  }
}
