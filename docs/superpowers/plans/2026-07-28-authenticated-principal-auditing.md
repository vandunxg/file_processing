# Authenticated Principal Auditing Implementation Plan

<!-- prettier-ignore -->
> [!WARNING]
> **LEGACY ARCHITECTURE NOTICE — SUPERSEDED ARCHITECTURE GUIDANCE**
>
> Tài liệu này được tạo trước quyết định chuyển sang Pragmatic Modular DDD.
> Các package `adapter/*`, `port/*`, `*UseCase`, `*Port` và `*Adapter` trong tài
> liệu này mô tả legacy implementation và **không còn là architecture guidance**.
>
> This document predates the migration to Pragmatic Modular DDD. Every
> `adapter/in`, `adapter/out`, `port/in`, `port/out`, `*UseCase`,
> `*RepositoryPort`, and `*PersistenceAdapter` reference below records the
> legacy implementation **as it was actually built**. It is a historical record,
> not an instruction. Do not reproduce this layout, naming, or interface
> structure in new code or in a refactor.
>
> [`RULE.md`](../../../RULE.md) §4 is the source of truth for architecture. The
> business behavior, API contracts, and security requirements described here
> remain valid; only the structural guidance is superseded.

> **PLAN COMPLETED — DO NOT RE-EXECUTE.** Every task in this plan was implemented and merged. Its package layout and type names follow the legacy Hexagonal structure and are superseded by `RULE.md` §4. Kept for history only.
>
> <sub>Original agent instruction, retained verbatim for the record: **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.</sub>

**Goal:** Persist the authenticated username in business-resource audit columns while preserving anonymous audit metadata for system event records.

**Architecture:** `CustomAuthenticationFilter` will replace the preliminary JWT principal with an immutable `AuthenticatedUser` carrying the resolved username, user ID, and session ID. The existing `UserAuthentication` and `SpringSecurityAuditorAware` remain in place, so `Authentication.getName()` supplies the username to Spring Data auditing. Controllers consume the new principal instead of parsing access-token claims.

**Tech Stack:** Java 21, Spring Boot 4, Spring Security, Spring Data JPA auditing, Maven, JUnit 5, Mockito, MockMvc, Testcontainers PostgreSQL.

## Global Constraints

- Keep the JWT contract unchanged: `sub` is the user UUID and `sid` is the session UUID claim.
- Keep `UserAuthentication` as the security-context authentication type for shared `SecurityUtils` compatibility.
- Do not accept an audit username from request input or add a database migration.
- `audit_logs` and `action_logs` keep `created_by` and `last_modified_by` as `anonymous`; their actor fields remain `changedBy` and `username`.
- Do not add dependencies or replace `SpringSecurityAuditorAware`.
- Do not create commits unless the user explicitly requests them.

---

## File Structure

| File | Responsibility |
|---|---|
| `auth/configuration/security/AuthenticatedUser.java` | Immutable authenticated principal containing the identity needed by controllers and JPA auditing. |
| `auth/configuration/filter/CustomAuthenticationFilter.java` | Resolve the current user and replace the raw JWT principal with `AuthenticatedUser`. |
| `auth/adapter/in/web/AuthController.java` | Use the principal for access-token password change and logout identities. |
| `auth/adapter/in/web/CurrentUserController.java` | Use the principal user ID for `/me`. |
| `auth/adapter/in/web/CurrentUserSessionController.java` | Use the principal user ID and session ID for self-service session actions. |
| `auth/adapter/in/web/UserManagementController.java` | Use the principal user ID as the administrative actor. |
| `auth/configuration/filter/CustomAuthenticationFilterTest.java` | Verify the filter installs a username-backed principal. |
| `auth/adapter/in/web/*ContractTest.java` | Keep controller signatures and permission annotations aligned with the principal contract. |
| `auth/adapter/in/web/RoleManagementControllerIT.java` | Prove a business role write persists the caller username in JPA audit fields. |
| `auth/adapter/in/web/AdminManagementControllerIT.java` | Prove a managed-user write is attributed and event-record audit metadata remains anonymous. |

### Task 1: Install A Username-Backed Principal

**Files:**
- Create: `src/main/java/com/vandunxg/file_processing/auth/configuration/security/AuthenticatedUser.java`
- Modify: `src/main/java/com/vandunxg/file_processing/auth/configuration/filter/CustomAuthenticationFilter.java:30-72`
- Test: `src/test/java/com/vandunxg/file_processing/auth/configuration/filter/CustomAuthenticationFilterTest.java:56-113`

**Interfaces:**
- Consumes: `RequestAuthenticationResult(UUID userId, String username, List<String> permissions)` and the validated JWT `sid` claim.
- Produces: `AuthenticatedUser(UUID userId, String username, UUID sessionId, Collection<? extends GrantedAuthority> authorities)` as the `UserAuthentication` principal.

- [ ] **Step 1: Write the failing filter test**

  Add the session ID to the test JWT and replace the raw-JWT assertion in `refreshesAuthoritiesFromTheApplicationUseCase`:

  ```java
  UUID userId = UUID.randomUUID();
  UUID sessionId = UUID.randomUUID();
  Jwt jwt = jwt(userId, sessionId);
  // existing SecurityContext setup and mocked result stay unchanged

  Authentication refreshed = SecurityContextHolder.getContext().getAuthentication();
  assertThat(refreshed).isInstanceOf(UserAuthentication.class);
  assertThat(refreshed.getPrincipal()).isInstanceOf(AuthenticatedUser.class);
  AuthenticatedUser principal = (AuthenticatedUser) refreshed.getPrincipal();
  assertThat(principal.userId()).isEqualTo(userId);
  assertThat(principal.getUsername()).isEqualTo("operator01");
  assertThat(principal.sessionId()).isEqualTo(sessionId);
  assertThat(refreshed.getName()).isEqualTo("operator01");
  ```

  Change the test helper to construct a JWT with both identity claims:

  ```java
  private static Jwt jwt(UUID userId, UUID sessionId) {
    Instant now = Instant.parse("2026-07-27T00:00:00Z");
    return Jwt.withTokenValue("test-token")
        .header("alg", "RS256")
        .subject(userId.toString())
        .claim("sid", sessionId.toString())
        .issuedAt(now)
        .expiresAt(now.plusSeconds(60))
        .build();
  }
  ```

- [ ] **Step 2: Run the focused test to verify it fails**

  Run: `./mvnw -Dtest=CustomAuthenticationFilterTest test`

  Expected: compilation failure because `AuthenticatedUser` does not exist, or assertion failure because the principal is still the JWT.

- [ ] **Step 3: Add the minimal principal**

  Create `AuthenticatedUser.java` as an immutable `UserDetails`; preserve the supplied authority collection and make account flags always true because account status was already validated by `ResolveRequestAuthenticationService`:

  ```java
  package com.vandunxg.file_processing.auth.configuration.security;

  import java.util.Collection;
  import java.util.List;
  import java.util.Objects;
  import java.util.UUID;

  import org.springframework.security.core.GrantedAuthority;
  import org.springframework.security.core.userdetails.UserDetails;

  public record AuthenticatedUser(
      UUID userId,
      String username,
      UUID sessionId,
      Collection<? extends GrantedAuthority> authorities)
      implements UserDetails {

    public AuthenticatedUser {
      Objects.requireNonNull(userId, "userId");
      Objects.requireNonNull(username, "username");
      Objects.requireNonNull(sessionId, "sessionId");
      authorities = List.copyOf(authorities);
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
      return authorities;
    }

    @Override
    public String getPassword() {
      return "";
    }

    @Override
    public String getUsername() {
      return username;
    }
  }
  ```

- [ ] **Step 4: Replace the request principal in the authentication filter**

  In `CustomAuthenticationFilter`, retain the request username attribute for `ActionLoggingFilter`, add `SESSION_ID_CLAIM = "sid"`, and build the new principal after resolving current permissions:

  ```java
  Jwt token = (Jwt) authentication.getPrincipal();
  UUID sessionId = UUID.fromString(token.getClaimAsString(SESSION_ID_CLAIM));
  AuthenticatedUser principal =
      new AuthenticatedUser(result.userId(), result.username(), sessionId, authorities);

  request.setAttribute(AUTHENTICATED_USERNAME_ATTRIBUTE, result.username());
  SecurityContextHolder.getContext()
      .setAuthentication(
          new UserAuthentication(
              principal, token, authorities, result.userId(), token.getTokenValue()));
  ```

  Import `AuthenticatedUser` and `UUID`. Do not change the JWT converter; it must continue to produce the preliminary `UserAuthentication` whose principal is a `Jwt`.

- [ ] **Step 5: Run the focused test to verify it passes**

  Run: `./mvnw -Dtest=CustomAuthenticationFilterTest test`

  Expected: `Tests run: 3, Failures: 0, Errors: 0`.

### Task 2: Migrate Access-Token Controllers To The Principal

**Files:**
- Modify: `src/main/java/com/vandunxg/file_processing/auth/adapter/in/web/AuthController.java:123-145,215-246`
- Modify: `src/main/java/com/vandunxg/file_processing/auth/adapter/in/web/CurrentUserController.java:30-40`
- Modify: `src/main/java/com/vandunxg/file_processing/auth/adapter/in/web/CurrentUserSessionController.java:42-92`
- Modify: `src/main/java/com/vandunxg/file_processing/auth/adapter/in/web/UserManagementController.java:44-137`
- Modify: `src/test/java/com/vandunxg/file_processing/auth/adapter/in/web/CurrentUserControllerContractTest.java:23-29`
- Modify: `src/test/java/com/vandunxg/file_processing/auth/adapter/in/web/CurrentUserSessionControllerContractTest.java:22-68`
- Test: `src/test/java/com/vandunxg/file_processing/auth/adapter/in/web/AuthControllerIT.java`
- Test: `src/test/java/com/vandunxg/file_processing/auth/adapter/in/web/CurrentUserControllerContractTest.java`
- Test: `src/test/java/com/vandunxg/file_processing/auth/adapter/in/web/CurrentUserSessionControllerContractTest.java`

**Interfaces:**
- Consumes: `@AuthenticationPrincipal AuthenticatedUser principal` from Task 1.
- Produces: unchanged HTTP routes, authorization annotations, request bodies, and response contracts.

- [ ] **Step 1: Update contract tests before controller signatures**

  Replace imports of `Jwt` with `AuthenticatedUser`, and assert the same routes and permissions against the new signatures:

  ```java
  assertThat(
          controller.getMethod("listSessions", AuthenticatedUser.class)
              .getAnnotation(GetMapping.class)
              .value())
      .isEmpty();
  assertPermission(controller.getMethod("me", AuthenticatedUser.class), "user:self_read");
  assertPermission(
      controller.getMethod(
          "revokeSession", UUID.class, AuthenticatedUser.class, HttpServletRequest.class),
      "session:self_delete");
  ```

- [ ] **Step 2: Run contract tests to verify they fail**

  Run: `./mvnw -Dtest=CurrentUserControllerContractTest,CurrentUserSessionControllerContractTest test`

  Expected: reflection failures because controller methods still declare `Jwt`.

- [ ] **Step 3: Replace access-token JWT parameters with `AuthenticatedUser`**

  In the four controllers, import `AuthenticatedUser` and apply these substitutions:

  ```java
  // AuthController: authenticated access-token operations only
  public void changePassword(
      @Valid @RequestBody ChangePasswordRequest request,
      @AuthenticationPrincipal AuthenticatedUser principal,
      HttpServletRequest http) {
    changePasswordUseCase.change(webMapper.toCommand(request, principal.userId(), clientIp(http)));
  }

  public void logout(
      @AuthenticationPrincipal AuthenticatedUser principal,
      HttpServletRequest http,
      HttpServletResponse response) {
    logoutUseCase.logout(
        LogoutCommand.builder()
            .sessionId(principal.sessionId())
            .userId(principal.userId())
            .ipAddress(clientIp(http))
            .build());
    clearAuthCookies(response);
  }

  // CurrentUserSessionController
  public Response<List<SessionResponse>> listSessions(
      @AuthenticationPrincipal AuthenticatedUser principal) {
    var results = listSessionsUseCase.list(
        ListSessionsQuery.builder()
            .userId(principal.userId())
            .currentSessionId(principal.sessionId())
            .build());
    return Response.of(results.stream().map(webMapper::toResponse).toList());
  }

  // CurrentUserController
  public Response<MeResponse> me(@AuthenticationPrincipal AuthenticatedUser principal) {
    return Response.of(webMapper.toResponse(
        getCurrentUserUseCase.me(GetCurrentUserQuery.builder().userId(principal.userId()).build())));
  }
  ```

  Apply the same `principal.userId()` replacement to every administrative action in
  `UserManagementController`. Remove only the `subjectAsUuid(Jwt)` and
  `sidAsUuid(Jwt)` helpers that no longer have callers. Keep
  `completePasswordChange`, which validates a distinct password-change JWT from
  the request header, unchanged.

- [ ] **Step 4: Run controller tests to verify behavior remains intact**

  Run: `./mvnw -Dtest=AuthControllerIT,CurrentUserControllerContractTest,CurrentUserSessionControllerContractTest,AdminManagementControllerIT test`

  Expected: all selected tests pass; route and permission annotations are unchanged.

### Task 3: Verify Business Audit Attribution And System Event Boundaries

**Files:**
- Modify: `src/test/java/com/vandunxg/file_processing/auth/adapter/in/web/RoleManagementControllerIT.java:131-190`
- Modify: `src/test/java/com/vandunxg/file_processing/auth/adapter/in/web/AdminManagementControllerIT.java:54-243`

**Interfaces:**
- Consumes: authenticated HTTP requests through the complete security filter chain and existing `RoleRepositoryPort`, `UserRepositoryPort`, `AuditLogPort`, and `ActionLogPort`.
- Produces: regression coverage for username attribution on business data and anonymous auditing on event records.

- [ ] **Step 1: Write failing business-audit integration assertions**

  In each integration-test class, replace the token helper return type with a local fixture so the test retains the authenticating username:

  ```java
  private record AccessToken(String value, String username) {}
  ```

  Make the helper create the existing user with a named variable and return:

  ```java
  return new AccessToken(
      jwtIssuerPort.issue(
          saved.getId(), session.getId(), saved.getCredentialVersion(),
          List.of(roleCode), permissions, now).token(),
      saved.getUsername());
  ```

  Update existing headers to call `.value()`. Add a role-create test using a
  `role:create` token, then reload the role and assert:

  ```java
  assertThat(
          jdbcTemplate.queryForObject(
              "select created_by from role where id = ?", String.class, savedRole.getId()))
      .isEqualTo(actor.username());
  assertThat(
          jdbcTemplate.queryForObject(
              "select last_modified_by from role where id = ?", String.class, savedRole.getId()))
      .isEqualTo(actor.username());
  ```

  Add an admin-user-create test using a `user:create` token and assert
  `auth_users.created_by` and `auth_users.last_modified_by` equal the admin
  actor username. Create the role with a first actor, then update it with a
  second `role:update` actor; assert `role.created_by` remains the first
  username and `role.last_modified_by` becomes the second username.

- [ ] **Step 2: Run the new integration tests to verify they fail**

  Run: `./mvnw -Dtest=RoleManagementControllerIT,AdminManagementControllerIT test`

  Expected: assertions report `anonymous` where the actor username is expected
  before Task 1 changes the authentication principal.

- [ ] **Step 3: Assert event-record audit metadata stays anonymous**

  In `AdminManagementControllerIT`, inject `JdbcTemplate` and extend the
  existing direct-record tests. Immediately after `auditLogPort.record(auditLog)`
  and `actionLogPort.record(actionLog)`, query their audit columns by ID:

  ```java
  assertThat(
          jdbcTemplate.queryForObject(
              "select created_by from audit_logs where id = ?", String.class, auditLog.getId()))
      .isEqualTo("anonymous");
  assertThat(
          jdbcTemplate.queryForObject(
              "select created_by from action_logs where id = ?", String.class, actionLog.getId()))
      .isEqualTo("anonymous");
  ```

  Do not change event publishers, listeners, entities, or migrations; this test
  documents the approved system-record behavior.

- [ ] **Step 4: Run the focused integration tests to verify they pass**

  Run: `./mvnw -Dtest=RoleManagementControllerIT,AdminManagementControllerIT test`

  Expected: all tests pass, including username attribution for `role` and
  `auth_users` plus `anonymous` event-record metadata.

- [ ] **Step 5: Run final verification**

  Run: `./mvnw spotless:check test`

  Expected: formatting succeeds and the full test suite passes.

## Self-Review

- **Spec coverage:** Task 1 preserves the existing auditor and installs a username-backed principal; Task 2 migrates every controller using access-token `Jwt` while retaining password-change JWT processing; Task 3 verifies business-resource attribution and the explicitly approved audit/action-log boundary. No migration, JWT claim, dependency, or custom auditor is included.
- **Placeholder scan:** No unresolved work markers or unspecified test behavior remain.
- **Type consistency:** `AuthenticatedUser` is consistently the principal type; it exposes `userId()`, `sessionId()`, and `getUsername()`. The raw `Jwt` remains only in the preliminary authentication and password-change flow.
