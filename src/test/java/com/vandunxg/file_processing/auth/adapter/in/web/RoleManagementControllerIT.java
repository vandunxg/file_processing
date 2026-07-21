package com.vandunxg.file_processing.auth.adapter.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.vandunxg.common.utils.HashUtils;
import com.vandunxg.file_processing.auth.application.port.out.JwtIssuerPort;
import com.vandunxg.file_processing.auth.application.port.out.RoleRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.SessionRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.UserRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.UserRoleRepositoryPort;
import com.vandunxg.file_processing.auth.domain.model.Role;
import com.vandunxg.file_processing.auth.domain.model.Session;
import com.vandunxg.file_processing.auth.domain.model.User;
import com.vandunxg.file_processing.testsupport.AuthIntegrationTestBase;
import com.vandunxg.file_processing.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@PostgresIntegrationTest
@AutoConfigureMockMvc
class RoleManagementControllerIT extends AuthIntegrationTestBase {

  @Autowired private MockMvc mockMvc;
  @Autowired private RoleRepositoryPort roleRepositoryPort;
  @Autowired private UserRepositoryPort userRepositoryPort;
  @Autowired private UserRoleRepositoryPort userRoleRepositoryPort;
  @Autowired private SessionRepositoryPort sessionRepositoryPort;
  @Autowired private JwtIssuerPort jwtIssuerPort;

  @Test
  void listReturnsPagedRolesForACallerWithRoleReadPermission() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/roles")
                .header("Authorization", "Bearer " + accessToken("ADMIN", List.of("role:read")))
                .queryParam("keyword", "admin")
                .queryParam("sortBy", "code.asc"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page.pageIndex").value(1))
        .andExpect(jsonPath("$.data[0].code").value("ADMIN"));
  }

  @Test
  void listAppliesDomainSortByDirection() throws Exception {
    Instant now = Instant.now();
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    Role firstByCode =
        roleRepositoryPort.save(Role.create("SORT_AAA_" + suffix, "A", null, now));
    roleRepositoryPort.save(
        Role.create("SORT_ZZZ_" + suffix, "Z", null, now.plusSeconds(1)));

    mockMvc
        .perform(
            get("/api/v1/roles")
                .header("Authorization", "Bearer " + accessToken("ADMIN", List.of("role:read")))
                .queryParam("keyword", "SORT")
                .queryParam("sortBy", "code.asc"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data[0].code").value(firstByCode.getCode()));
  }

  @Test
  void listAppliesRequestedPageAndPageSize() throws Exception {
    Instant now = Instant.now();
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    roleRepositoryPort.save(Role.create("PAGE_AAA_" + suffix, "A", null, now));
    Role secondByCode =
        roleRepositoryPort.save(
            Role.create("PAGE_ZZZ_" + suffix, "Z", null, now.plusSeconds(1)));

    mockMvc
        .perform(
            get("/api/v1/roles")
                .header("Authorization", "Bearer " + accessToken("ADMIN", List.of("role:read")))
                .queryParam("keyword", "PAGE")
                .queryParam("pageIndex", "2")
                .queryParam("pageSize", "1")
                .queryParam("sortBy", "code.asc"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page.pageIndex").value(2))
        .andExpect(jsonPath("$.page.pageSize").value(1))
        .andExpect(jsonPath("$.page.total").value(2))
        .andExpect(jsonPath("$.data[0].code").value(secondByCode.getCode()));
  }

  @Test
  void listRejectsSortFieldsOutsideTheRoleDomainContract() throws Exception {
    mockMvc
        .perform(
            get("/api/v1/roles")
                .header("Authorization", "Bearer " + accessToken("ADMIN", List.of("role:read")))
                .queryParam("sortBy", "deletedAt.desc"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("sortBy"));
  }

  private String accessToken(String roleCode, List<String> permissions) {
    Role role = roleRepositoryPort.findByCode(roleCode).orElseThrow();
    Instant now = Instant.now();
    User user =
        User.adminCreate(
            "token-" + System.nanoTime(),
            "token-" + UUID.randomUUID() + "@example.com",
            "Token User",
            "{bcrypt}$2a$stubhash",
            Set.of(role),
            true,
            now);
    User saved = userRepositoryPort.save(user);
    userRoleRepositoryPort.replaceRoles(saved.getId(), Set.of(role.getId()), now);
    Session session =
        Session.issue(
            UUID.randomUUID(),
            saved.getId(),
            saved.getCredentialVersion(),
            "JUnit",
            null,
            now,
            Duration.ofHours(1));
    sessionRepositoryPort.save(
        session,
        HashUtils.sha256(("refresh-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8)));
    return jwtIssuerPort
        .issue(
            saved.getId(),
            session.getId(),
            saved.getCredentialVersion(),
            List.of(roleCode),
            permissions,
            now)
        .token();
  }
}
