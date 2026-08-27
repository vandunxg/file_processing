package com.vandunxg.file_processing.auth.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.vandunxg.file_processing.auth.application.AuditTrail;
import com.vandunxg.file_processing.auth.application.capability.AuditLogEventPublisher;
import com.vandunxg.file_processing.auth.application.capability.CredentialVersionCache;
import com.vandunxg.file_processing.auth.application.command.SetRoleInheritanceCommand;
import com.vandunxg.file_processing.auth.application.command.UpdateRoleCommand;
import com.vandunxg.file_processing.auth.application.exception.AuthErrorCode;
import com.vandunxg.file_processing.auth.application.exception.AuthException;
import com.vandunxg.file_processing.auth.domain.RoleRepository;
import com.vandunxg.file_processing.auth.domain.SessionRepository;
import com.vandunxg.file_processing.auth.domain.UserRepository;
import com.vandunxg.file_processing.auth.domain.model.ActiveStatus;
import com.vandunxg.file_processing.auth.domain.model.Role;
import com.vandunxg.file_processing.auth.domain.model.User;
import com.vandunxg.file_processing.auth.domain.model.UserStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class RoleAdminCommandServiceTest {

  @Mock private RoleRepository roleRepository;
  @Mock private UserRepository userRepository;
  @Mock private SessionRepository sessionRepository;
  @Mock private CredentialVersionCache credentialVersionCache;
  @Mock private AuditLogEventPublisher auditLogEventPublisher;

  @Test
  void setInheritanceRejectsAParentThatIsAlreadyAChild() {
    UUID parentId = UUID.randomUUID();
    UUID childId = UUID.randomUUID();
    Role parent = role(parentId, "PARENT", childId);
    Role child = role(childId, "CHILD", null);
    when(roleRepository.findById(childId)).thenReturn(java.util.Optional.of(child));
    when(roleRepository.findAll()).thenReturn(List.of(parent, child));

    RoleAdminCommandService service =
        new RoleAdminCommandService(
            roleRepository,
            userRepository,
            sessionRepository,
            credentialVersionCache,
            new AuditTrail(auditLogEventPublisher),
            Clock.fixed(Instant.parse("2026-07-20T12:00:00Z"), ZoneOffset.UTC));

    assertThatThrownBy(
            () ->
                service.setInheritance(
                    new SetRoleInheritanceCommand(UUID.randomUUID(), childId, parentId)))
        .isInstanceOf(AuthException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.ROLE_INHERITANCE_CYCLE);
  }

  @Test
  void updateInvalidatesEveryAssignedUsersSessionsAndCredentialCache() {
    UUID roleId = UUID.randomUUID();
    UUID userId = UUID.randomUUID();
    Role role = role(roleId, "AUDITOR", null);
    User user =
        User.builder()
            .id(userId)
            .status(UserStatus.ACTIVE)
            .roles(java.util.Set.of(role))
            .credentialVersion(1)
            .build();
    when(roleRepository.findById(roleId)).thenReturn(java.util.Optional.of(role));
    when(roleRepository.findByCode("AUDITOR")).thenReturn(java.util.Optional.of(role));
    when(roleRepository.findAll()).thenReturn(List.of(role));
    when(roleRepository.findActiveUserIdsByRoleIds(java.util.Set.of(roleId)))
        .thenReturn(List.of(userId));
    when(userRepository.findByIdForUpdate(userId)).thenReturn(java.util.Optional.of(user));
    when(roleRepository.save(any(Role.class))).thenAnswer(invocation -> invocation.getArgument(0));
    when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
    TransactionSynchronizationManager.initSynchronization();

    newService()
        .update(
            new UpdateRoleCommand(
                UUID.randomUUID(), roleId, "AUDITOR", "Auditor", null, java.util.Set.of()));

    assertThat(user.getCredentialVersion()).isEqualTo(2);
    verify(sessionRepository)
        .revokeAllForUser(
            eq(userId),
            eq(com.vandunxg.file_processing.auth.domain.model.RevocationReason.ADMIN),
            any());
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(TransactionSynchronization::afterCommit);
    verify(credentialVersionCache).invalidate(userId);
    TransactionSynchronizationManager.clearSynchronization();
  }

  private RoleAdminCommandService newService() {
    return new RoleAdminCommandService(
        roleRepository,
        userRepository,
        sessionRepository,
        credentialVersionCache,
        new AuditTrail(auditLogEventPublisher),
        Clock.fixed(Instant.parse("2026-07-20T12:00:00Z"), ZoneOffset.UTC));
  }

  private static Role role(UUID id, String code, UUID inheritedRoleId) {
    return Role.builder()
        .id(id)
        .code(code)
        .name(code)
        .roleInheritedId(inheritedRoleId)
        .status(ActiveStatus.ACTIVE)
        .build();
  }
}
