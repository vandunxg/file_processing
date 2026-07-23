# Auth Role Search Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `GET /api/v1/roles` a validated, paged role-code search that follows the auth module's hexagonal boundaries.

**Architecture:** The web adapter maps `RoleSearchRequest` to an application `RoleSearchQuery` and calls `SearchRolesUseCase`. The use case returns `PageDTO<Role>` without web dependencies; the controller maps it to `PagingResponse<RoleResponse>`. The persistence fragment keeps using `BaseEntityRepositoryCustom` and provides only safe predicates and parameters.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Data JPA, MapStruct, Jakarta Validation, PostgreSQL Testcontainers, `common-models`, and `common-persistence`.

## Global Constraints

- Use `auth.domain.model.ActiveStatus` in the web request and application query; never the duplicate common enum.
- `sortBy` is a `Role` domain contract: `code`, `name`, `status`, and `createdAt`, each with `.asc` or `.desc`.
- Reuse `PagingRequest`, `PagingQuery`, `PageDTO`, `PagingResponse`, `SqlUtils`, `StrUtils`, and `BaseEntityRepositoryCustom`.
- Keep `application/` free of web DTOs, MapStruct, Spring Web, and JPA types.
- Do not add a schema migration, dependency, custom repository base, or a web-only duplicate enum.
- Do not commit unless the user explicitly requests it.

---

### Task 1: Establish the inbound search boundary

**Files:**
- Create: `src/main/java/com/vandunxg/file_processing/auth/application/port/in/SearchRolesUseCase.java`
- Modify: `src/main/java/com/vandunxg/file_processing/auth/application/service/RoleManagementService.java`
- Modify: `src/test/java/com/vandunxg/file_processing/auth/application/service/RoleManagementServiceTest.java`

**Interfaces:**
- Consumes: `RoleSearchQuery`, `RoleRepositoryPort.count(RoleSearchQuery)`, and `RoleRepositoryPort.search(RoleSearchQuery)`.
- Produces: `SearchRolesUseCase.search(RoleSearchQuery) -> PageDTO<Role>`.

- [ ] **Step 1: Write failing use-case tests**

Add these tests to `RoleManagementServiceTest`:

```java
@Test
void searchReturnsDomainRolesWithTheRequestedPageMetadata() {
  RoleSearchQuery query = RoleSearchQuery.builder()
      .pageIndex(2).pageSize(10).status(ActiveStatus.ACTIVE).build();
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
```

- [ ] **Step 2: Run the new tests and verify they fail**

Run: `./mvnw -Dtest=RoleManagementServiceTest test`

Expected: the test does not compile because `search` returns `PageDTO<RoleResponse>` and there is no inbound use-case port.

- [ ] **Step 3: Add the port and return a domain page**

Create the port:

```java
public interface SearchRolesUseCase {
  PageDTO<Role> search(RoleSearchQuery query);
}
```

Make `RoleManagementService` implement `SearchRolesUseCase`; remove its web-DTO import and implement:

```java
@Override
@Transactional(readOnly = true)
public PageDTO<Role> search(RoleSearchQuery query) {
  long count = roleRepositoryPort.count(query);
  if (count == 0) {
    return PageDTO.of(List.of(), query.getPageIndex(), query.getPageSize(), 0);
  }
  return PageDTO.of(
      roleRepositoryPort.search(query), query.getPageIndex(), query.getPageSize(), count);
}
```

- [ ] **Step 4: Run the use-case tests and verify they pass**

Run: `./mvnw -Dtest=RoleManagementServiceTest test`

Expected: `BUILD SUCCESS` and both search scenarios pass.

### Task 2: Repair the library-backed persistence search

**Files:**
- Modify: `src/main/java/com/vandunxg/file_processing/auth/application/query/RoleSearchQuery.java`
- Modify: `src/main/java/com/vandunxg/file_processing/auth/adapter/out/persistence/entity/custom/JpaRoleRepositoryCustomImpl.java`
- Delete: `src/main/java/com/vandunxg/file_processing/auth/adapter/out/persistence/BaseEntityRepository.java`
- Create: `src/test/java/com/vandunxg/file_processing/auth/adapter/out/persistence/RolePersistenceAdapterIT.java`

**Interfaces:**
- Consumes: `RoleRepositoryPort` and the standard `BaseEntityRepositoryCustom` implementation.
- Produces: correct `count` and `search` results for live roles using domain `ActiveStatus` and encoded role-code keyword parameters.

- [ ] **Step 1: Write the failing PostgreSQL integration test**

Create `RolePersistenceAdapterIT`, extending `AuthIntegrationTestBase`, with `@PostgresIntegrationTest` and an injected `RoleRepositoryPort`. Persist one active role and one deleted role, then assert partial keyword and status filtering:

```java
@Test
void searchReturnsOnlyLiveRolesMatchingKeywordAndStatus() {
  Instant now = Instant.now();
  Role live = roleRepositoryPort.save(Role.create("SEARCH_READER", "Search Reader", "Reads logs", now));
  Role deleted = Role.create("SEARCH_DELETED", "Search Deleted", "Reads logs", now);
  deleted.inactivate();
  deleted.delete(now);
  roleRepositoryPort.save(deleted);

  RoleSearchQuery query = RoleSearchQuery.builder()
      .keyword("reader").status(ActiveStatus.ACTIVE)
      .pageIndex(1).pageSize(10).sortBy("code.asc").build();

  assertThat(roleRepositoryPort.count(query)).isEqualTo(1);
  assertThat(roleRepositoryPort.search(query)).extracting(Role::getId).containsExactly(live.getId());
}
```

- [ ] **Step 2: Run the persistence test and verify it fails**

Run: `./mvnw -Dtest=RolePersistenceAdapterIT test`

Expected: the test fails because the current predicate selects `deletedAt is not null`, uses the wrong status enum, and binds an unencoded keyword.

- [ ] **Step 3: Use the correct domain enum and library helpers**

Replace the common `ActiveStatus` import in `RoleSearchQuery` with `auth.domain.model.ActiveStatus`. In `JpaRoleRepositoryCustomImpl`, use only this predicate construction:

```java
StringBuilder sql = new StringBuilder(" WHERE E.deletedAt is null ");
if (StrUtils.isNotBlank(query.getKeyword())) {
  sql.append(" AND (E.code like :keyword or E.name like :keyword or E.description like :keyword) ");
  values.put("keyword", SqlUtils.encodeKeyword(query.getKeyword()));
}
if (query.getStatus() != null) {
  sql.append(" AND E.status = :status ");
  values.put("status", query.getStatus());
}
```

Delete the unused local `BaseEntityRepository`; the shared library already supplies this capability.

- [ ] **Step 4: Run the persistence test and verify it passes**

Run: `./mvnw -Dtest=RolePersistenceAdapterIT test`

Expected: `BUILD SUCCESS`; the result contains only the active role and accepts the unescaped partial keyword `reader`.

### Task 3: Expose the validated hexagonal HTTP endpoint

**Files:**
- Modify: `src/main/java/com/vandunxg/file_processing/auth/adapter/in/web/dto/request/RoleSearchRequest.java`
- Modify: `src/main/java/com/vandunxg/file_processing/auth/adapter/in/web/dto/response/RoleResponse.java`
- Modify: `src/main/java/com/vandunxg/file_processing/auth/adapter/in/web/mapper/RoleWebMapper.java`
- Modify: `src/main/java/com/vandunxg/file_processing/auth/adapter/in/web/RoleManagementController.java`
- Modify: `src/main/resources/i18n/messages.properties`
- Modify: `src/main/resources/i18n/messages_vi.properties`
- Create: `src/test/java/com/vandunxg/file_processing/auth/adapter/in/web/RoleManagementControllerIT.java`

**Interfaces:**
- Consumes: `SearchRolesUseCase.search(RoleSearchQuery) -> PageDTO<Role>` and `RoleWebMapper`.
- Produces: `GET /api/v1/roles` with a `PagingResponse<RoleResponse>` body, `role:read` authorization, and validated domain `sortBy` values.

- [ ] **Step 1: Write failing web contract tests**

Create `RoleManagementControllerIT` using the existing `AdminManagementControllerIT` token helper pattern. Assert a permitted caller receives a paged result and an invalid domain sort is rejected:

```java
mockMvc.perform(get("/api/v1/roles")
        .header("Authorization", "Bearer " + accessToken("ADMIN", List.of("role:read")))
        .queryParam("keyword", "admin")
        .queryParam("sortBy", "code.asc"))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.page.pageIndex").value(1))
    .andExpect(jsonPath("$.data[0].code").value("ADMIN"));

mockMvc.perform(get("/api/v1/roles")
        .header("Authorization", "Bearer " + accessToken("ADMIN", List.of("role:read")))
        .queryParam("sortBy", "deletedAt.desc"))
    .andExpect(status().isBadRequest());
```

- [ ] **Step 2: Run the web contract test and verify it fails**

Run: `./mvnw -Dtest=RoleManagementControllerIT test`

Expected: the invalid sort is accepted and the controller still calls the concrete service and returns application-mapped response DTOs.

- [ ] **Step 3: Keep HTTP concerns in the web adapter**

Make `RoleSearchRequest` use domain `ActiveStatus`, then validate its inherited sort getter with a single domain-field pattern:

```java
private static final String ROLE_SORT_PATTERN =
    "^(?:(?:code|name|status|createdAt)\\.(?:asc|desc))(?:,(?:code|name|status|createdAt)\\.(?:asc|desc))*$";

@Override
@Pattern(regexp = ROLE_SORT_PATTERN, message = "{ROLE_SORT_INVALID}")
public String getSortBy() {
  return super.getSortBy();
}
```

Add `ROLE_SORT_INVALID` to both message bundles. Add `RoleResponse toResponse(Role role)` and `List<RoleResponse> toResponse(List<Role> roles)` to `RoleWebMapper`; map `isConst`, `status`, and each `RolePermission` authority there. Remove DTO conversion from the application service.

Inject `SearchRolesUseCase` into `RoleManagementController`. Mark the list request `@Valid` and return:

```java
return PagingResponse.of(
    searchRolesUseCase.search(roleWebMapper.toQuery(request)), roleWebMapper::toResponse);
```

Use the same mapper for the controller's existing role response conversions so `RoleResponse` no longer owns conversion logic.

- [ ] **Step 4: Run endpoint, persistence, and application tests**

Run: `./mvnw -Dtest='RoleManagementServiceTest,RolePersistenceAdapterIT,RoleManagementControllerIT' test`

Expected: `BUILD SUCCESS` with unit, PostgreSQL, and MockMvc coverage passing.

- [ ] **Step 5: Format only touched files and run final verification**

Run: `./mvnw spotless:apply -DspotlessFiles='src/main/java/com/vandunxg/file_processing/auth/**,src/test/java/com/vandunxg/file_processing/auth/**'`

Run: `./mvnw test`

Run: `./mvnw spotless:check`

Expected: all tests pass. If Spotless reports only pre-existing unrelated worktree changes, report them without modifying or reverting those files.
