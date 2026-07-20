package com.vandunxg.file_processing.auth.adapter.in.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@PostgresIntegrationTest
@AutoConfigureMockMvc
class AdminManagementControllerIT extends AuthIntegrationTestBase {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private RoleRepositoryPort roleRepositoryPort;
  @Autowired private UserRepositoryPort userRepositoryPort;
  @Autowired private UserRoleRepositoryPort userRoleRepositoryPort;
  @Autowired private SessionRepositoryPort sessionRepositoryPort;
  @Autowired private JwtIssuerPort jwtIssuerPort;

  @Test
  void createUserRequiresTheUserCreatePermission() throws Exception {
    Role operator = roleRepositoryPort.findByCode("OPERATOR").orElseThrow();
    String payload =
        objectMapper.writeValueAsString(
            new UserManagementController.CreateUserRequest(
                "managed-" + System.nanoTime(),
                "managed-" + UUID.randomUUID() + "@example.com",
                "Managed User",
                "StrongPassw0rd!",
                Set.of(operator.getId()),
                true));

    mockMvc
        .perform(
            post("/api/v1/users")
                .header(
                    "Authorization", "Bearer " + accessToken("OPERATOR", List.of("file:self_read")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
        .andExpect(status().isForbidden());

    mockMvc
        .perform(
            post("/api/v1/users")
                .header("Authorization", "Bearer " + accessToken("ADMIN", List.of("all:manage")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
        .andExpect(status().isCreated());
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
