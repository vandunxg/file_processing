package com.vandunxg.file_processing.auth.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import com.vandunxg.common.models.dto.PageDTO;
import com.vandunxg.file_processing.auth.application.capability.RoleSearchRepository;
import com.vandunxg.file_processing.auth.application.query.RoleSearchQuery;
import com.vandunxg.file_processing.auth.domain.RoleRepository;
import com.vandunxg.file_processing.auth.domain.model.ActiveStatus;
import com.vandunxg.file_processing.auth.domain.model.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RoleAdminQueryServiceTest {

  @Mock private RoleRepository roleRepository;
  @Mock private RoleSearchRepository roleSearchRepository;

  @InjectMocks private RoleAdminQueryService roleAdminQueryService;

  @Test
  void searchReturnsDomainRolesWithTheRequestedPageMetadata() {
    RoleSearchQuery query = RoleSearchQuery.builder().pageIndex(2).pageSize(10).build();
    Role role = role(UUID.randomUUID(), "AUDITOR");
    when(roleSearchRepository.count(query)).thenReturn(11L);
    when(roleSearchRepository.search(query)).thenReturn(List.of(role));

    PageDTO<Role> result = roleAdminQueryService.search(query);

    assertThat(result.getData()).containsExactly(role);
    assertThat(result.getPage().getPageIndex()).isEqualTo(2);
    assertThat(result.getPage().getPageSize()).isEqualTo(10);
    assertThat(result.getPage().getTotal()).isEqualTo(11);
  }

  @Test
  void searchPreservesRequestedMetadataWhenNoRoleMatches() {
    RoleSearchQuery query = RoleSearchQuery.builder().pageIndex(4).pageSize(15).build();
    when(roleSearchRepository.count(query)).thenReturn(0L);

    PageDTO<Role> result = roleAdminQueryService.search(query);

    assertThat(result.getData()).isEmpty();
    assertThat(result.getPage().getPageIndex()).isEqualTo(4);
    assertThat(result.getPage().getPageSize()).isEqualTo(15);
    assertThat(result.getPage().getTotal()).isZero();
    verify(roleSearchRepository, never()).search(query);
  }

  private static Role role(UUID id, String code) {
    return Role.builder().id(id).code(code).name(code).status(ActiveStatus.ACTIVE).build();
  }
}
