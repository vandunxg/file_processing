package com.vandunxg.file_processing.auth.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.vandunxg.common.utils.HashUtils;
import com.vandunxg.common.utils.IdUtils;
import com.vandunxg.file_processing.auth.application.capability.TokenIssuer;
import com.vandunxg.file_processing.auth.domain.ActionLogRepository;
import com.vandunxg.file_processing.auth.domain.AuditLogRepository;
import com.vandunxg.file_processing.auth.domain.RoleRepository;
import com.vandunxg.file_processing.auth.domain.SessionRepository;
import com.vandunxg.file_processing.auth.domain.UserRepository;
import com.vandunxg.file_processing.auth.domain.model.ActionLog;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import com.vandunxg.file_processing.auth.domain.model.AuditLogDomain;
import com.vandunxg.file_processing.auth.domain.model.OperationType;
import com.vandunxg.file_processing.auth.domain.model.Role;
import com.vandunxg.file_processing.auth.domain.model.Session;
import com.vandunxg.file_processing.auth.domain.model.User;
import com.vandunxg.file_processing.testsupport.AuthIntegrationTestBase;
import com.vandunxg.file_processing.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

@PostgresIntegrationTest
@AutoConfigureMockMvc
class AdminManagementControllerIT extends AuthIntegrationTestBase {

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private RoleRepository roleRepository;
  @Autowired private UserRepository userRepository;
  @Autowired private SessionRepository sessionRepository;
  @Autowired private TokenIssuer tokenIssuer;
  @Autowired private AuditLogRepository auditLogRepository;
  @Autowired private ActionLogRepository actionLogRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void createUserRequiresTheUserCreatePermission() throws Exception {
    Role operator = roleRepository.findByCode("OPERATOR").orElseThrow();
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

  @Test
  void createUserUsesTheAuthenticatedUsernameForAuditColumns() throws Exception {
    Role operator = roleRepository.findByCode("OPERATOR").orElseThrow();
    String managedUsername = "managed-" + System.nanoTime();
    AccessToken actor = authenticatedUser("ADMIN", List.of("user:create"));
    String payload =
        objectMapper.writeValueAsString(
            new UserManagementController.CreateUserRequest(
                managedUsername,
                managedUsername + "@example.com",
                "Managed User",
                "StrongPassw0rd!",
                Set.of(operator.getId()),
                true));

    mockMvc
        .perform(
            post("/api/v1/users")
                .header("Authorization", "Bearer " + actor.value())
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload))
        .andExpect(status().isCreated());

    assertThat(
            jdbcTemplate.queryForObject(
                "select created_by from auth_users where username = ?",
                String.class,
                managedUsername))
        .isEqualTo(actor.username());
    assertThat(
            jdbcTemplate.queryForObject(
                "select last_modified_by from auth_users where username = ?",
                String.class,
                managedUsername))
        .isEqualTo(actor.username());
  }

  @Test
  void listUsersReturnsPagedSearchResults() throws Exception {
    Role operator = roleRepository.findByCode("OPERATOR").orElseThrow();
    Instant now = Instant.now();
    String suffix = UUID.randomUUID().toString().substring(0, 8);
    User first =
        userRepository.save(
            User.adminCreate(
                "paged-a-" + suffix,
                "paged-a-" + suffix + "@example.com",
                "Paged Alpha",
                "{bcrypt}$2a$stubhash",
                Set.of(operator),
                true,
                now));
    User second =
        userRepository.save(
            User.adminCreate(
                "paged-z-" + suffix,
                "paged-z-" + suffix + "@example.com",
                "Paged Zulu",
                "{bcrypt}$2a$stubhash",
                Set.of(operator),
                true,
                now));
    userRepository.replaceRoles(first.getId(), Set.of(operator.getId()), now);
    userRepository.replaceRoles(second.getId(), Set.of(operator.getId()), now);

    mockMvc
        .perform(
            get("/api/v1/users")
                .header("Authorization", "Bearer " + accessToken("ADMIN", List.of("user:read")))
                .queryParam("keyword", "paged-")
                .queryParam("pageIndex", "2")
                .queryParam("pageSize", "1")
                .queryParam("sortBy", "username.asc"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page.pageIndex").value(2))
        .andExpect(jsonPath("$.page.pageSize").value(1))
        .andExpect(jsonPath("$.page.total").value(2))
        .andExpect(jsonPath("$.data[0].username").value(second.getUsername()));
  }

  @Test
  void listAuditLogsReturnsPagedSearchResults() throws Exception {
    Instant newer = Instant.parse("2099-01-01T00:00:00Z");
    AuditLog successfulLogin =
        AuditLog.builder()
            .id(IdUtils.nextId())
            .domain(AuditLogDomain.AUTH)
            .objectId(UUID.randomUUID())
            .operation(OperationType.LOGIN_SUCCEEDED)
            .changedBy(UUID.randomUUID())
            .changedAt(newer.minusSeconds(1))
            .build();
    AuditLog failedLogin =
        AuditLog.builder()
            .id(IdUtils.nextId())
            .domain(AuditLogDomain.AUTH)
            .objectId(UUID.randomUUID())
            .operation(OperationType.LOGIN_FAILED)
            .changedBy(UUID.randomUUID())
            .changedAt(newer)
            .build();
    auditLogRepository.record(successfulLogin);
    auditLogRepository.record(failedLogin);

    assertThat(
            jdbcTemplate.queryForObject(
                "select created_by from audit_logs where id = ?",
                String.class,
                failedLogin.getId()))
        .isEqualTo("anonymous");

    mockMvc
        .perform(
            get("/api/v1/admin/audit-logs")
                .header("Authorization", "Bearer " + accessToken("ADMIN", List.of("audit:read")))
                .queryParam("pageIndex", "1")
                .queryParam("pageSize", "1")
                .queryParam("keyword", "LOGIN_FAILED")
                .queryParam("sortBy", "changedAt.desc"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page.pageIndex").value(1))
        .andExpect(jsonPath("$.page.pageSize").value(1))
        .andExpect(jsonPath("$.page.total").value(1))
        .andExpect(jsonPath("$.data[0].operation").value("LOGIN_FAILED"));
  }

  @Test
  void listActionLogsReturnsFilteredResultsWithRawRequestAndErrorData() throws Exception {
    Instant startTime = Instant.parse("2099-01-01T00:00:00Z");
    ActionLog actionLog =
        ActionLog.builder()
            .id(IdUtils.nextId())
            .userId(UUID.randomUUID())
            .username("action-log-user")
            .startTime(startTime)
            .endTime(startTime.plusMillis(25))
            .duration(25L)
            .path("/api/v1/jobs")
            .apiDoc("Read processing job")
            .requestMethod("GET")
            .ipAddress("203.0.113.10")
            .userAgent("JUnit")
            .requestData("{\"externalId\":\"customer-1\"}")
            .statusCode(500)
            .errorMessage("Database batch failed")
            .requestParam("{\"include\":[\"details\"]}")
            .build();
    actionLogRepository.record(actionLog);

    assertThat(
            jdbcTemplate.queryForObject(
                "select created_by from action_logs where id = ?", String.class, actionLog.getId()))
        .isEqualTo("anonymous");

    mockMvc
        .perform(
            get("/api/v1/admin/action-logs")
                .header(
                    "Authorization", "Bearer " + accessToken("ADMIN", List.of("action_log:read")))
                .queryParam("username", "log-user")
                .queryParam("apiDoc", "processing")
                .queryParam("errorMessage", "batch failed")
                .queryParam("requestMethod", "GET")
                .queryParam("startTimeFrom", "2098-12-31T00:00:00Z")
                .queryParam("startTimeTo", "2099-01-02T00:00:00Z"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page.total").value(1))
        .andExpect(jsonPath("$.data[0].id").value(actionLog.getId().toString()))
        .andExpect(jsonPath("$.data[0].requestData").value(actionLog.getRequestData()))
        .andExpect(jsonPath("$.data[0].requestParam").value(actionLog.getRequestParam()))
        .andExpect(jsonPath("$.data[0].errorMessage").value(actionLog.getErrorMessage()));
  }

  private String accessToken(String roleCode, List<String> permissions) {
    return authenticatedUser(roleCode, permissions).value();
  }

  private AccessToken authenticatedUser(String roleCode, List<String> permissions) {
    Role role = roleRepository.findByCode(roleCode).orElseThrow();
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
    User saved = userRepository.save(user);
    userRepository.replaceRoles(saved.getId(), Set.of(role.getId()), now);
    Session session =
        Session.issue(
            UUID.randomUUID(),
            saved.getId(),
            saved.getCredentialVersion(),
            "JUnit",
            null,
            now,
            Duration.ofHours(1));
    sessionRepository.save(
        session,
        HashUtils.sha256(("refresh-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8)));
    return new AccessToken(
        tokenIssuer
            .issue(
                saved.getId(),
                session.getId(),
                saved.getCredentialVersion(),
                List.of(roleCode),
                permissions,
                now)
            .token(),
        saved.getUsername());
  }

  private record AccessToken(String value, String username) {}
}
