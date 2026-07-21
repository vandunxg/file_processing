package com.vandunxg.file_processing.auth.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import com.vandunxg.common.models.dto.PageDTO;
import com.vandunxg.file_processing.auth.application.command.SetRoleInheritanceCommand;
import com.vandunxg.file_processing.auth.application.command.UpdateRoleCommand;
import com.vandunxg.file_processing.auth.application.port.out.AuditLogEventPublisherPort;
import com.vandunxg.file_processing.auth.application.port.out.CredentialVersionCachePort;
import com.vandunxg.file_processing.auth.application.port.out.RoleRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.SessionRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.UserRepositoryPort;
import com.vandunxg.file_processing.auth.application.query.RoleSearchQuery;
import com.vandunxg.file_processing.auth.domain.exception.AuthDomainException;
import com.vandunxg.file_processing.auth.domain.exception.AuthErrorCode;
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
class RoleManagementServiceTest {

  @Mock private RoleRepositoryPort roleRepositoryPort;
  @Mock private UserRepositoryPort userRepositoryPort;
  @Mock private SessionRepositoryPort sessionRepositoryPort;
  @Mock private CredentialVersionCachePort credentialVersionCachePort;
  @Mock private AuditLogEventPublisherPort auditLogEventPublisherPort;

  @Test
  void searchReturnsDomainRolesWithTheRequestedPageMetadata() {
    RoleSearchQuery query = RoleSearchQuery.builder().pageIndex(2).pageSize(10).build();
    Role role = role(UUID.randomUUID(), "AUDITOR", null);
    when(roleRepositoryPort.count(query)).thenReturn(11L);
    when(roleRepositoryPort.search(query)).thenReturn(List.of(role));

    PageDTO<Role> result = newService().search(query);

    assertThat(result.getData()).containsExactly(role);
    assertThat(result.getPage().getPageIndex()).isEqualTo(2);
    assertThat(result.getPage().getPageSize()).isEqualTo(10);
    assertThat(result.getPage().getTotal()).isEqualTo(11);
  }

  @Test
  void searchPreservesRequestedMetadataWhenNoRoleMatches() {
    RoleSearchQuery query = RoleSearchQuery.builder().pageIndex(4).pageSize(15).build();
    when(roleRepositoryPort.count(query)).thenReturn(0L);

    PageDTO<Role> result = newService().search(query);

    assertThat(result.getData()).isEmpty();
    assertThat(result.getPage().getPageIndex()).isEqualTo(4);
    assertThat(result.getPage().getPageSize()).isEqualTo(15);
    assertThat(result.getPage().getTotal()).isZero();
    verify(roleRepositoryPort, never()).search(query);
  }

  @Test
  void setInheritanceRejectsAParentThatIsAlreadyAChild() {
    UUID parentId = UUID.randomUUID();
    UUID childId = UUID.randomUUID();
    Role parent = role(parentId, "PARENT", childId);
    Role child = role(childId, "CHILD", null);
    when(roleRepositoryPort.findById(childId)).thenReturn(java.util.Optional.of(child));
    when(roleRepositoryPort.findAll()).thenReturn(List.of(parent, child));

    RoleManagementService service =
        new RoleManagementService(
            roleRepositoryPort,
            userRepositoryPort,
            sessionRepositoryPort,
            credentialVersionCachePort,
            auditLogEventPublisherPort,
            Clock.fixed(Instant.parse("2026-07-20T12:00:00Z"), ZoneOffset.UTC));

    assertThatThrownBy(
            () ->
                service.setInheritance(
                    new SetRoleInheritanceCommand(UUID.randomUUID(), childId, parentId)))
        .isInstanceOf(AuthDomainException.class)
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
    when(roleRepositoryPort.findById(roleId)).thenReturn(java.util.Optional.of(role));
    when(roleRepositoryPort.findByCode("AUDITOR")).thenReturn(java.util.Optional.of(role));
    when(roleRepositoryPort.findAll()).thenReturn(List.of(role));
    when(roleRepositoryPort.findActiveUserIdsByRoleIds(java.util.Set.of(roleId)))
        .thenReturn(List.of(userId));
    when(userRepositoryPort.findByIdForUpdate(userId)).thenReturn(java.util.Optional.of(user));
    when(roleRepositoryPort.save(any(Role.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(userRepositoryPort.save(any(User.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    TransactionSynchronizationManager.initSynchronization();

    newService()
        .update(
            new UpdateRoleCommand(
                UUID.randomUUID(), roleId, "AUDITOR", "Auditor", null, java.util.Set.of()));

    assertThat(user.getCredentialVersion()).isEqualTo(2);
    verify(sessionRepositoryPort)
        .revokeAllForUser(
            eq(userId),
            eq(com.vandunxg.file_processing.auth.domain.model.RevocationReason.ADMIN),
            any());
    TransactionSynchronizationManager.getSynchronizations()
        .forEach(TransactionSynchronization::afterCommit);
    verify(credentialVersionCachePort).invalidate(userId);
    TransactionSynchronizationManager.clearSynchronization();
  }

  private RoleManagementService newService() {
    return new RoleManagementService(
        roleRepositoryPort,
        userRepositoryPort,
        sessionRepositoryPort,
        credentialVersionCachePort,
        auditLogEventPublisherPort,
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
