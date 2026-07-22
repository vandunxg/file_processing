# Paging Search Convention Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make unbounded admin list endpoints follow the project's paging/search convention.

**Architecture:** Keep controllers thin: web request DTOs map to application query DTOs, application services return `PageDTO`, persistence adapters delegate count/search to custom repositories. Reuse common `PagingRequest`, `PagingQuery`, `PagingResponse`, and `@ValidatePaging`; do not add a new framework.

**Tech Stack:** Java 21, Spring Boot 4, Spring MVC, Spring Data JPA, MapStruct, common-models/common-persistence.

## Global Constraints

- Only convert unbounded admin list APIs.
- Skip bounded catalogs, enum lists, JWKS, `/me`, and current-user session lists.
- Keep `@ValidatePaging(sortModel = Entity.class)` as the sort validation boundary.
- Add deterministic tests for converted behavior.

---

### Task 1: Document The Convention

**Files:**
- Modify: `RULE.md`
- Modify: `RULE_vi.md`

**Interfaces:**
- Consumes: existing `RULE.md` API and rejected-pattern sections.
- Produces: documented rule for unbounded list/search/completion endpoints.

- [ ] Add a short API convention section: unbounded list/search/completion endpoints must use `PagingRequest`, `@ValidatePaging`, `PagingQuery`, repository `count/search`, and `PagingResponse`.
- [ ] Add the same rule in Vietnamese.
- [ ] Mention explicit skips for bounded catalog/current-user endpoints.

### Task 2: Convert Managed User List

**Files:**
- Create: `src/main/java/com/vandunxg/file_processing/auth/adapter/in/web/dto/request/UserSearchRequest.java`
- Create: `src/main/java/com/vandunxg/file_processing/auth/application/query/UserSearchQuery.java`
- Modify: `src/main/java/com/vandunxg/file_processing/auth/adapter/in/web/UserManagementController.java`
- Modify: `src/main/java/com/vandunxg/file_processing/auth/application/service/AdminUserService.java`
- Modify: `src/main/java/com/vandunxg/file_processing/auth/application/port/out/UserRepositoryPort.java`
- Modify: `src/main/java/com/vandunxg/file_processing/auth/adapter/out/persistence/UserPersistenceAdapter.java`
- Modify: `src/main/java/com/vandunxg/file_processing/auth/adapter/out/persistence/entity/JpaUserRepository.java`
- Create/modify custom user repository if needed.
- Test: `src/test/java/com/vandunxg/file_processing/auth/adapter/in/web/AdminManagementControllerIT.java` or `UserManagementControllerIT.java` if present.

**Interfaces:**
- Produces: `PageDTO<User> AdminUserService.search(UserSearchQuery query)`.
- Produces: `long UserRepositoryPort.count(UserSearchQuery query)` and `List<User> search(UserSearchQuery query)`.

- [ ] Write a failing controller/integration test for `GET /api/v1/users?keyword=...&pageIndex=1&pageSize=1&sortBy=username.asc` returning `PagingResponse` metadata.
- [ ] Add request/query DTOs.
- [ ] Replace controller list return type with `PagingResponse<UserResponse>`.
- [ ] Add service and repository count/search methods.
- [ ] Implement keyword search over username/email/displayName and live-row filtering.
- [ ] Run the focused user controller/persistence tests.

### Task 3: Convert Admin Audit Log List

**Files:**
- Create: `src/main/java/com/vandunxg/file_processing/auth/adapter/in/web/dto/request/AuditLogSearchRequest.java`
- Create: `src/main/java/com/vandunxg/file_processing/auth/application/query/AuditLogSearchQuery.java`
- Modify: `src/main/java/com/vandunxg/file_processing/auth/adapter/in/web/AuditLogController.java`
- Modify: `src/main/java/com/vandunxg/file_processing/auth/application/service/AuditReadService.java`
- Modify: audit repository port/adapter/entity repository files.
- Test: add focused audit controller or persistence tests.

**Interfaces:**
- Produces: `PageDTO<AuditLog> AuditReadService.search(AuditLogSearchQuery query)`.
- Produces: audit repository `count/search` methods.

- [ ] Write a failing test for `GET /api/v1/admin/audit-logs?pageIndex=1&pageSize=1&sortBy=changedAt.desc` returning paging metadata.
- [ ] Add request/query DTOs.
- [ ] Replace controller list return type with `PagingResponse<AuditLogResponse>`.
- [ ] Add service and repository count/search methods.
- [ ] Implement live-row filtering and optional keyword/filter support where the entity already has fields.
- [ ] Run focused audit tests.

### Task 4: Verification

**Files:**
- Verify changed source and tests only.

- [ ] Run `mvn -Dtest=RoleManagementControllerIT,AdminManagementControllerIT test` plus any new/renamed tests.
- [ ] Run `mvn -DskipTests compile`.
- [ ] Report any existing warnings separately from failures.

## Self-Review

- Spec coverage: rules, users, audit logs, and tests are covered.
- Placeholder scan: no deferred behavior; implementation details are bounded to existing patterns.
- Type consistency: request DTOs extend `PagingRequest`; query DTOs extend `PagingQuery`; controllers return `PagingResponse`.
