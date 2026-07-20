package com.vandunxg.file_processing.auth.application.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.vandunxg.common.utils.IdUtils;
import com.vandunxg.file_processing.auth.application.port.out.AuditLogEventPublisherPort;
import com.vandunxg.file_processing.auth.application.port.out.CredentialVersionCachePort;
import com.vandunxg.file_processing.auth.application.port.out.PasswordHasherPort;
import com.vandunxg.file_processing.auth.application.port.out.RoleRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.SessionRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.UserRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.UserRoleRepositoryPort;
import com.vandunxg.file_processing.auth.domain.exception.AuthDomainException;
import com.vandunxg.file_processing.auth.domain.exception.AuthErrorCode;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import com.vandunxg.file_processing.auth.domain.model.AuditLogDomain;
import com.vandunxg.file_processing.auth.domain.model.OperationType;
import com.vandunxg.file_processing.auth.domain.model.RevocationReason;
import com.vandunxg.file_processing.auth.domain.model.Role;
import com.vandunxg.file_processing.auth.domain.model.User;
import com.vandunxg.file_processing.auth.domain.model.UserStatus;
import com.vandunxg.file_processing.auth.domain.policy.PasswordPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
public class AdminUserService {

  private final UserRepositoryPort userRepositoryPort;
  private final RoleRepositoryPort roleRepositoryPort;
  private final UserRoleRepositoryPort userRoleRepositoryPort;
  private final PasswordHasherPort passwordHasherPort;
  private final SessionRepositoryPort sessionRepositoryPort;
  private final CredentialVersionCachePort credentialVersionCachePort;
  private final AuditLogEventPublisherPort auditLogEventPublisherPort;
  private final Clock clock;
  private final PasswordPolicy passwordPolicy = new PasswordPolicy();

  @Transactional
  public User create(
      UUID actorId,
      String username,
      String email,
      String displayName,
      String temporaryPassword,
      Set<UUID> roleIds,
      boolean autoVerifyEmail) {
    String normalizedUsername = User.normalize(username);
    String normalizedEmail = User.normalize(email);
    if (userRepositoryPort.existsByNormalizedUsername(normalizedUsername)) {
      throw new AuthDomainException(AuthErrorCode.USERNAME_ALREADY_EXISTS);
    }
    if (userRepositoryPort.existsByNormalizedEmail(normalizedEmail)) {
      throw new AuthDomainException(AuthErrorCode.EMAIL_ALREADY_EXISTS);
    }
    if (!passwordPolicy.validate(temporaryPassword, normalizedUsername, normalizedEmail).valid()) {
      throw new AuthDomainException(AuthErrorCode.PASSWORD_POLICY_VIOLATION);
    }
    Instant now = Instant.now(clock);
    User user =
        User.adminCreate(
            username,
            email,
            displayName,
            passwordHasherPort.hash(temporaryPassword),
            resolveRoles(roleIds),
            autoVerifyEmail,
            now);
    User saved = userRepositoryPort.save(user);
    userRoleRepositoryPort.replaceRoles(saved.getId(), roleIds, now);
    publishAfterCommit(audit(actorId, saved.getId(), OperationType.USER_REGISTERED, now));
    return saved;
  }

  @Transactional(readOnly = true)
  public List<User> list() {
    return userRepositoryPort.findAll();
  }

  @Transactional(readOnly = true)
  public User detail(UUID userId) {
    return userRepositoryPort
        .findById(userId)
        .orElseThrow(() -> new AuthDomainException(AuthErrorCode.USER_NOT_FOUND));
  }

  @Transactional
  public User update(
      UUID actorId, UUID userId, String email, String displayName, Set<UUID> roleIds) {
    roleRepositoryPort.lockAdminRole();
    User user = lockedUser(userId);
    Set<Role> roles = resolveRoles(roleIds);
    if (userRepositoryPort.existsByNormalizedEmail(User.normalize(email))
        && !user.getNormalizedEmail().equals(User.normalize(email))) {
      throw new AuthDomainException(AuthErrorCode.EMAIL_ALREADY_EXISTS);
    }
    boolean rolesChanged = !roleIds(user).equals(roleIds);
    assertLastActiveAdminIsPreserved(user, user.getStatus(), roles);
    user.updateByAdmin(email, displayName, roles);
    Instant now = Instant.now(clock);
    if (rolesChanged) {
      user.invalidateCredentials();
    }
    User saved = userRepositoryPort.save(user);
    if (rolesChanged) {
      userRoleRepositoryPort.replaceRoles(userId, roleIds, now);
      revokeAndInvalidateAfterCommit(saved, now);
      publishAfterCommit(audit(actorId, userId, OperationType.ROLE_ASSIGNED, now));
    } else {
      publishAfterCommit(audit(actorId, userId, OperationType.UPDATE, now));
    }
    return saved;
  }

  @Transactional
  public User disable(UUID actorId, UUID userId) {
    roleRepositoryPort.lockAdminRole();
    User user = lockedUser(userId);
    assertLastActiveAdminIsPreserved(user, UserStatus.DISABLED, user.getRoles());
    if (user.getStatus() == UserStatus.DISABLED) {
      return user;
    }
    Instant now = Instant.now(clock);
    user.disable();
    user.invalidateCredentials();
    User saved = userRepositoryPort.save(user);
    revokeAndInvalidateAfterCommit(saved, now);
    publishAfterCommit(audit(actorId, userId, OperationType.ACCOUNT_DISABLED, now));
    return saved;
  }

  @Transactional
  public User enable(UUID actorId, UUID userId) {
    User user = lockedUser(userId);
    if (user.getStatus() != UserStatus.DISABLED) {
      return user;
    }
    Instant now = Instant.now(clock);
    user.enable();
    user.invalidateCredentials();
    User saved = userRepositoryPort.save(user);
    revokeAndInvalidateAfterCommit(saved, now);
    publishAfterCommit(audit(actorId, userId, OperationType.ACCOUNT_ENABLED, now));
    return saved;
  }

  @Transactional
  public User unlock(UUID actorId, UUID userId) {
    User user = lockedUser(userId);
    user.unlock();
    Instant now = Instant.now(clock);
    User saved = userRepositoryPort.save(user);
    publishAfterCommit(audit(actorId, userId, OperationType.ACCOUNT_UNLOCKED, now));
    return saved;
  }

  @Transactional
  public void resetTemporaryPassword(UUID actorId, UUID userId, String temporaryPassword) {
    User user = lockedUser(userId);
    if (!passwordPolicy
        .validate(temporaryPassword, user.getNormalizedUsername(), user.getNormalizedEmail())
        .valid()) {
      throw new AuthDomainException(AuthErrorCode.PASSWORD_POLICY_VIOLATION);
    }
    Instant now = Instant.now(clock);
    user.resetTemporaryPassword(passwordHasherPort.hash(temporaryPassword), now);
    userRepositoryPort.save(user);
    revokeAndInvalidateAfterCommit(user, now);
    publishAfterCommit(audit(actorId, userId, OperationType.PASSWORD_RESET_COMPLETED, now));
  }

  private Set<Role> resolveRoles(Set<UUID> roleIds) {
    if (roleIds == null || roleIds.isEmpty()) {
      throw new AuthDomainException(AuthErrorCode.INVALID_ROLE);
    }
    List<Role> roles = roleRepositoryPort.findByIds(roleIds);
    if (roles.size() != roleIds.size() || roles.stream().anyMatch(role -> !role.isActive())) {
      throw new AuthDomainException(AuthErrorCode.INVALID_ROLE);
    }
    return Set.copyOf(roles);
  }

  private User lockedUser(UUID userId) {
    return userRepositoryPort
        .findByIdForUpdate(userId)
        .orElseThrow(() -> new AuthDomainException(AuthErrorCode.USER_NOT_FOUND));
  }

  private void assertLastActiveAdminIsPreserved(
      User current, UserStatus proposedStatus, Set<Role> proposedRoles) {
    boolean isActiveAdmin = current.isActive() && hasAdminRole(current.getRoles());
    boolean remainsActiveAdmin = proposedStatus == UserStatus.ACTIVE && hasAdminRole(proposedRoles);
    if (isActiveAdmin && !remainsActiveAdmin && userRepositoryPort.countActiveAdmins() <= 1) {
      throw new AuthDomainException(AuthErrorCode.LAST_ACTIVE_ADMIN);
    }
  }

  private static boolean hasAdminRole(Set<Role> roles) {
    return roles.stream().anyMatch(role -> "ADMIN".equals(role.getCode()));
  }

  private static Set<UUID> roleIds(User user) {
    return user.getRoles().stream().map(Role::getId).collect(Collectors.toSet());
  }

  private void revokeAndInvalidateAfterCommit(User user, Instant now) {
    sessionRepositoryPort.revokeAllForUser(user.getId(), RevocationReason.ADMIN, now);
    afterCommit(() -> credentialVersionCachePort.invalidate(user.getId()));
  }

  private AuditLog audit(UUID actorId, UUID userId, OperationType operation, Instant now) {
    return AuditLog.builder()
        .id(IdUtils.nextId())
        .domain(AuditLogDomain.USER)
        .objectId(userId)
        .operation(operation)
        .changedBy(actorId)
        .changedAt(now)
        .build();
  }

  private void publishAfterCommit(AuditLog audit) {
    afterCommit(() -> auditLogEventPublisherPort.publish(audit));
  }

  private static void afterCommit(Runnable action) {
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            action.run();
          }
        });
  }
}
