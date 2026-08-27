package com.vandunxg.file_processing.auth.application.service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.vandunxg.common.utils.IdUtils;
import com.vandunxg.file_processing.auth.application.AfterCommit;
import com.vandunxg.file_processing.auth.application.AuditTrail;
import com.vandunxg.file_processing.auth.application.capability.CredentialVersionCache;
import com.vandunxg.file_processing.auth.application.capability.PasswordHasher;
import com.vandunxg.file_processing.auth.application.exception.AuthErrorCode;
import com.vandunxg.file_processing.auth.application.exception.AuthException;
import com.vandunxg.file_processing.auth.domain.RoleRepository;
import com.vandunxg.file_processing.auth.domain.SessionRepository;
import com.vandunxg.file_processing.auth.domain.UserRepository;
import com.vandunxg.file_processing.auth.domain.exception.AuthRuleViolation;
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

/** Administrative writes over users: provisioning, profile and role changes, account state. */
@Service
@RequiredArgsConstructor
public class UserAdminCommandService {

  private final UserRepository userRepository;
  private final RoleRepository roleRepository;
  private final PasswordHasher passwordHasher;
  private final SessionRepository sessionRepository;
  private final CredentialVersionCache credentialVersionCache;
  private final AuditTrail auditTrail;
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
    if (userRepository.existsByNormalizedUsername(normalizedUsername)) {
      throw new AuthException(AuthErrorCode.AUTH_USERNAME_ALREADY_EXISTS);
    }
    if (userRepository.existsByNormalizedEmail(normalizedEmail)) {
      throw new AuthException(AuthErrorCode.AUTH_EMAIL_ALREADY_EXISTS);
    }
    if (!passwordPolicy.validate(temporaryPassword, normalizedUsername, normalizedEmail).valid()) {
      throw new AuthException(AuthErrorCode.AUTH_PASSWORD_POLICY_VIOLATION);
    }
    Instant now = Instant.now(clock);
    User user;
    try {
      user =
          User.adminCreate(
              username,
              email,
              displayName,
              passwordHasher.hash(temporaryPassword),
              resolveRoles(roleIds),
              autoVerifyEmail,
              now);
    } catch (AuthRuleViolation violation) {
      throw AuthException.of(violation);
    }
    User saved = userRepository.save(user);
    userRepository.replaceRoles(saved.getId(), roleIds, now);
    auditTrail.recordAfterCommit(audit(actorId, saved.getId(), OperationType.USER_REGISTERED, now));
    return saved;
  }

  @Transactional
  public User update(
      UUID actorId, UUID userId, String email, String displayName, Set<UUID> roleIds) {
    roleRepository.lockAdminRole();
    User user = lockedUser(userId);
    Set<Role> roles = resolveRoles(roleIds);
    if (userRepository.existsByNormalizedEmail(User.normalize(email))
        && !user.getNormalizedEmail().equals(User.normalize(email))) {
      throw new AuthException(AuthErrorCode.AUTH_EMAIL_ALREADY_EXISTS);
    }
    boolean rolesChanged = !roleIds(user).equals(roleIds);
    assertLastActiveAdminIsPreserved(user, user.getStatus(), roles);
    try {
      user.updateByAdmin(email, displayName, roles);
    } catch (AuthRuleViolation violation) {
      throw AuthException.of(violation);
    }
    Instant now = Instant.now(clock);
    if (rolesChanged) {
      user.invalidateCredentials();
    }
    User saved = userRepository.save(user);
    if (rolesChanged) {
      userRepository.replaceRoles(userId, roleIds, now);
      revokeAndInvalidateAfterCommit(saved, now);
      auditTrail.recordAfterCommit(audit(actorId, userId, OperationType.ROLE_ASSIGNED, now));
    } else {
      auditTrail.recordAfterCommit(audit(actorId, userId, OperationType.UPDATE, now));
    }
    return saved;
  }

  @Transactional
  public User disable(UUID actorId, UUID userId) {
    roleRepository.lockAdminRole();
    User user = lockedUser(userId);
    assertLastActiveAdminIsPreserved(user, UserStatus.DISABLED, user.getRoles());
    if (user.getStatus() == UserStatus.DISABLED) {
      return user;
    }
    Instant now = Instant.now(clock);
    try {
      user.disable();
    } catch (AuthRuleViolation violation) {
      throw AuthException.of(violation);
    }
    user.invalidateCredentials();
    User saved = userRepository.save(user);
    revokeAndInvalidateAfterCommit(saved, now);
    auditTrail.recordAfterCommit(audit(actorId, userId, OperationType.ACCOUNT_DISABLED, now));
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
    User saved = userRepository.save(user);
    revokeAndInvalidateAfterCommit(saved, now);
    auditTrail.recordAfterCommit(audit(actorId, userId, OperationType.ACCOUNT_ENABLED, now));
    return saved;
  }

  @Transactional
  public User unlock(UUID actorId, UUID userId) {
    User user = lockedUser(userId);
    user.unlock();
    Instant now = Instant.now(clock);
    User saved = userRepository.save(user);
    auditTrail.recordAfterCommit(audit(actorId, userId, OperationType.ACCOUNT_UNLOCKED, now));
    return saved;
  }

  @Transactional
  public void resetTemporaryPassword(UUID actorId, UUID userId, String temporaryPassword) {
    User user = lockedUser(userId);
    if (!passwordPolicy
        .validate(temporaryPassword, user.getNormalizedUsername(), user.getNormalizedEmail())
        .valid()) {
      throw new AuthException(AuthErrorCode.AUTH_PASSWORD_POLICY_VIOLATION);
    }
    Instant now = Instant.now(clock);
    user.resetTemporaryPassword(passwordHasher.hash(temporaryPassword), now);
    userRepository.save(user);
    revokeAndInvalidateAfterCommit(user, now);
    auditTrail.recordAfterCommit(
        audit(actorId, userId, OperationType.PASSWORD_RESET_COMPLETED, now));
  }

  private Set<Role> resolveRoles(Set<UUID> roleIds) {
    if (roleIds == null || roleIds.isEmpty()) {
      throw new AuthException(AuthErrorCode.ROLE_INVALID);
    }
    List<Role> roles = roleRepository.findByIds(roleIds);
    if (roles.size() != roleIds.size() || roles.stream().anyMatch(role -> !role.isActive())) {
      throw new AuthException(AuthErrorCode.ROLE_INVALID);
    }
    return Set.copyOf(roles);
  }

  private User lockedUser(UUID userId) {
    return userRepository
        .findByIdForUpdate(userId)
        .orElseThrow(() -> new AuthException(AuthErrorCode.USER_NOT_FOUND));
  }

  private void assertLastActiveAdminIsPreserved(
      User current, UserStatus proposedStatus, Set<Role> proposedRoles) {
    boolean isActiveAdmin = current.isActive() && hasAdminRole(current.getRoles());
    boolean remainsActiveAdmin = proposedStatus == UserStatus.ACTIVE && hasAdminRole(proposedRoles);
    if (isActiveAdmin && !remainsActiveAdmin && userRepository.countActiveAdmins() <= 1) {
      throw new AuthException(AuthErrorCode.AUTH_LAST_ACTIVE_ADMIN);
    }
  }

  private static boolean hasAdminRole(Set<Role> roles) {
    return roles.stream().anyMatch(role -> "ADMIN".equals(role.getCode()));
  }

  private static Set<UUID> roleIds(User user) {
    return user.getRoles().stream().map(Role::getId).collect(Collectors.toSet());
  }

  private void revokeAndInvalidateAfterCommit(User user, Instant now) {
    sessionRepository.revokeAllForUser(user.getId(), RevocationReason.ADMIN, now);
    AfterCommit.run(() -> credentialVersionCache.invalidate(user.getId()));
  }

  private static AuditLog audit(UUID actorId, UUID userId, OperationType operation, Instant now) {
    return AuditLog.builder()
        .id(IdUtils.nextId())
        .domain(AuditLogDomain.USER)
        .objectId(userId)
        .operation(operation)
        .changedBy(actorId)
        .changedAt(now)
        .build();
  }
}
