package com.vandunxg.file_processing.auth.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.vandunxg.common.models.enums.Action;
import com.vandunxg.file_processing.auth.domain.RoleRepository;
import com.vandunxg.file_processing.auth.domain.model.ActiveStatus;
import com.vandunxg.file_processing.auth.domain.model.ResourceCode;
import com.vandunxg.file_processing.auth.domain.model.Role;
import com.vandunxg.file_processing.auth.domain.model.RolePermission;
import com.vandunxg.file_processing.auth.domain.model.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuthorityServiceTest {

  @Mock private RoleRepository roleRepository;

  @Test
  void resolvesOwnAndInheritedPermissionsForActiveRoles() {
    UUID parentId = UUID.randomUUID();
    Role parent =
        Role.builder()
            .id(parentId)
            .code("AUDITOR")
            .name("Auditor")
            .status(ActiveStatus.ACTIVE)
            .permissions(Set.of(RolePermission.grant(parentId, ResourceCode.AUDIT, Action.READ)))
            .build();
    Role child =
        Role.builder()
            .id(UUID.randomUUID())
            .code("SUPERVISOR")
            .name("Supervisor")
            .status(ActiveStatus.ACTIVE)
            .roleInheritedId(parentId)
            .permissions(
                Set.of(RolePermission.grant(UUID.randomUUID(), ResourceCode.USER, Action.READ)))
            .build();
    User user = User.builder().id(UUID.randomUUID()).roles(Set.of(child)).build();
    // Only the roles the user can actually reach are loaded, one inheritance generation per call,
    // so resolving authorities does not scale with how many roles the system has defined.
    when(roleRepository.findByIds(Set.of(child.getId()))).thenReturn(List.of(child));
    when(roleRepository.findByIds(Set.of(parentId))).thenReturn(List.of(parent));

    assertThat(new AuthorityService(roleRepository).permissionsFor(user))
        .containsExactlyInAnyOrder("audit:read", "user:read");
    verify(roleRepository, never()).findAll();
  }

  @Test
  void stopsWalkingWhenInheritanceLoopsBackOnItself() {
    UUID firstId = UUID.randomUUID();
    UUID secondId = UUID.randomUUID();
    Role first =
        Role.builder()
            .id(firstId)
            .code("FIRST")
            .name("First")
            .status(ActiveStatus.ACTIVE)
            .roleInheritedId(secondId)
            .permissions(Set.of(RolePermission.grant(firstId, ResourceCode.USER, Action.READ)))
            .build();
    Role second =
        Role.builder()
            .id(secondId)
            .code("SECOND")
            .name("Second")
            .status(ActiveStatus.ACTIVE)
            .roleInheritedId(firstId)
            .permissions(Set.of(RolePermission.grant(secondId, ResourceCode.AUDIT, Action.READ)))
            .build();
    User user = User.builder().id(UUID.randomUUID()).roles(Set.of(first)).build();
    when(roleRepository.findByIds(Set.of(firstId))).thenReturn(List.of(first));
    when(roleRepository.findByIds(Set.of(secondId))).thenReturn(List.of(second));

    assertThat(new AuthorityService(roleRepository).permissionsFor(user))
        .containsExactlyInAnyOrder("user:read", "audit:read");
  }
}
