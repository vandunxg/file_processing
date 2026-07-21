package com.vandunxg.file_processing.auth.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import com.vandunxg.file_processing.auth.application.port.out.RoleRepositoryPort;
import com.vandunxg.file_processing.auth.application.query.RoleSearchQuery;
import com.vandunxg.file_processing.auth.domain.model.ActiveStatus;
import com.vandunxg.file_processing.auth.domain.model.Role;
import com.vandunxg.file_processing.testsupport.AuthIntegrationTestBase;
import com.vandunxg.file_processing.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@PostgresIntegrationTest
class RolePersistenceAdapterIT extends AuthIntegrationTestBase {

  @Autowired private RoleRepositoryPort roleRepositoryPort;

  @Test
  void searchReturnsOnlyLiveRolesMatchingKeywordAndStatus() {
    Instant now = Instant.now();
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    Role live =
        roleRepositoryPort.save(
            Role.create("SEARCH_READER_" + suffix, "Search Reader", "Reads logs", now));
    Role deleted = Role.create("SEARCH_DELETED_" + suffix, "Search Deleted", "Reads logs", now);
    deleted.inactivate();
    deleted.delete(now);
    roleRepositoryPort.save(deleted);
    Role inactive = Role.create("SEARCH_INACTIVE_" + suffix, "Search Inactive", "Reads logs", now);
    inactive.inactivate();
    roleRepositoryPort.save(inactive);

    RoleSearchQuery query =
        RoleSearchQuery.builder()
            .keyword("reader")
            .status(ActiveStatus.ACTIVE)
            .pageIndex(1)
            .pageSize(10)
            .build();

    assertThat(roleRepositoryPort.count(query)).isEqualTo(1);
    assertThat(roleRepositoryPort.search(query)).extracting(Role::getId).containsExactly(live.getId());
  }
}
