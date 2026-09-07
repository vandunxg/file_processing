package com.vandunxg.file_processing.auth.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import com.vandunxg.file_processing.auth.application.capability.RoleSearchRepository;
import com.vandunxg.file_processing.auth.application.query.RoleSearchQuery;
import com.vandunxg.file_processing.auth.domain.RoleRepository;
import com.vandunxg.file_processing.auth.domain.model.ActiveStatus;
import com.vandunxg.file_processing.auth.domain.model.Role;
import com.vandunxg.file_processing.testsupport.AuthIntegrationTestBase;
import com.vandunxg.file_processing.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@PostgresIntegrationTest
class JpaRoleRepositoryIT extends AuthIntegrationTestBase {

  @Autowired private RoleRepository roleRepository;
  @Autowired private RoleSearchRepository roleSearchRepository;

  @Test
  void searchReturnsOnlyLiveRolesMatchingKeywordAndStatus() {
    Instant now = Instant.now();
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    Role live =
        roleRepository.save(
            Role.create("SEARCH_READER_" + suffix, "Search Reader", "Reads logs", now));
    Role deleted = Role.create("SEARCH_DELETED_" + suffix, "Search Deleted", "Reads logs", now);
    deleted.inactivate();
    deleted.delete(now);
    roleRepository.save(deleted);
    Role inactive = Role.create("SEARCH_INACTIVE_" + suffix, "Search Inactive", "Reads logs", now);
    inactive.inactivate();
    roleRepository.save(inactive);

    RoleSearchQuery query =
        RoleSearchQuery.builder()
            .keyword("reader")
            .status(ActiveStatus.ACTIVE)
            .pageIndex(1)
            .pageSize(10)
            .build();

    assertThat(roleSearchRepository.count(query)).isEqualTo(1);
    assertThat(roleSearchRepository.search(query))
        .extracting(Role::getId)
        .containsExactly(live.getId());
  }
}
