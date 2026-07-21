# Role Management Controller Refactor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refactor `RoleManagementController` to follow `RULE.md` boundaries while changing only `POST /api/v1/roles` from `200 OK` to `201 Created`.

**Architecture:** Keep the controller as a thin HTTP adapter. Add inbound ports and application command/result records, keep business behavior in the existing services, and use `RoleWebMapper` for non-trivial web/application mapping. Preserve existing endpoint paths and JSON shapes.

**Tech Stack:** Java 21, Spring Boot 4.1.x, Maven wrapper, Spring MVC, Spring Security method authorization, MapStruct, JUnit 5, MockMvc, Testcontainers PostgreSQL.

## Global Constraints

- Follow `AGENTS.md` for business behavior and `RULE.md` for implementation rules.
- Do not add runtime dependencies, frameworks, interfaces for trivial one-method classes, or speculative abstractions.
- Controllers contain request validation, mapping, use-case invocation, and response construction only.
- Controllers return the common `Response<T>` or `PagingResponse<T>` envelope.
- Use `@ResponseStatus(HttpStatus.CREATED)` for role creation; do not wrap the response in `ResponseEntity` only to set status.
- Do not change URI, HTTP method, authorization expression, request JSON fields, or response JSON fields except the role-create HTTP status.
- Do not commit unless the user explicitly asks for a commit.

---

## File Structure

- `src/test/java/com/vandunxg/file_processing/auth/adapter/in/web/RoleManagementControllerIT.java`: add the observable regression test for `201 Created`.
- `src/main/java/com/vandunxg/file_processing/auth/application/command/`: add role write command records.
- `src/main/java/com/vandunxg/file_processing/auth/application/result/`: add permission catalog result records.
- `src/main/java/com/vandunxg/file_processing/auth/application/port/in/`: add cohesive inbound ports for role management and permission catalog.
- `src/main/java/com/vandunxg/file_processing/auth/application/service/RoleManagementService.java`: implement role management port and accept command inputs.
- `src/main/java/com/vandunxg/file_processing/auth/application/service/PermissionCatalogService.java`: implement permission catalog port and return application results.
- `src/main/java/com/vandunxg/file_processing/auth/adapter/in/web/dto/request/`: move role write request records out of the controller.
- `src/main/java/com/vandunxg/file_processing/auth/adapter/in/web/dto/response/ResourcePermissionResponse.java`: add web DTO for `/roles/permissions` while preserving JSON fields.
- `src/main/java/com/vandunxg/file_processing/auth/adapter/in/web/mapper/RoleWebMapper.java`: map requests to commands and results/domain to response DTOs.
- `src/main/java/com/vandunxg/file_processing/auth/adapter/in/web/RoleManagementController.java`: depend on inbound ports, use `SecurityUtils`, and keep the controller thin.
- `src/test/java/com/vandunxg/file_processing/auth/application/service/RoleManagementServiceTest.java`: update direct service calls to command inputs.

---

### Task 1: Add The Create-Role Contract Test

**Files:**
- Modify: `src/test/java/com/vandunxg/file_processing/auth/adapter/in/web/RoleManagementControllerIT.java`

**Interfaces:**
- Consumes: existing `POST /api/v1/roles` endpoint and `accessToken(String roleCode, List<String> permissions)` helper.
- Produces: failing test `createReturnsCreatedRoleWithCreatedStatus()` proving the required HTTP status and response envelope.

- [ ] **Step 1: Add imports for POST requests and JSON content**

Update the static import block and imports near the top of `RoleManagementControllerIT.java`:

```java
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.http.MediaType;
```

- [ ] **Step 2: Add the failing test**

Insert this test after `listRejectsSortFieldsOutsideTheRoleDomainContract()`:

```java
  @Test
  void createReturnsCreatedRoleWithCreatedStatus() throws Exception {
    String code = "CREATE_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

    mockMvc
        .perform(
            post("/api/v1/roles")
                .header("Authorization", "Bearer " + accessToken("ADMIN", List.of("role:create")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "code": "%s",
                      "name": "Created Role",
                      "description": "Created by controller test",
                      "permissions": []
                    }
                    """
                        .formatted(code)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.code").value(code))
        .andExpect(jsonPath("$.data.name").value("Created Role"));
  }
```

- [ ] **Step 3: Run the new test and confirm it fails for the right reason**

Run: `./mvnw -Dtest=RoleManagementControllerIT#createReturnsCreatedRoleWithCreatedStatus test`

Expected: FAIL because the current endpoint returns `200 OK`, not `201 Created`.

---

### Task 2: Add Application Commands, Results, And Inbound Ports

**Files:**
- Create: `src/main/java/com/vandunxg/file_processing/auth/application/command/RolePermissionCommand.java`
- Create: `src/main/java/com/vandunxg/file_processing/auth/application/command/CreateRoleCommand.java`
- Create: `src/main/java/com/vandunxg/file_processing/auth/application/command/UpdateRoleCommand.java`
- Create: `src/main/java/com/vandunxg/file_processing/auth/application/command/SetRoleInheritanceCommand.java`
- Create: `src/main/java/com/vandunxg/file_processing/auth/application/command/RoleActionCommand.java`
- Create: `src/main/java/com/vandunxg/file_processing/auth/application/result/PermissionResourceResult.java`
- Create: `src/main/java/com/vandunxg/file_processing/auth/application/result/ResourcePermissionResult.java`
- Create: `src/main/java/com/vandunxg/file_processing/auth/application/port/in/RoleManagementUseCase.java`
- Create: `src/main/java/com/vandunxg/file_processing/auth/application/port/in/PermissionCatalogUseCase.java`

**Interfaces:**
- Consumes: `ResourceCode`, `Action`, `Role` domain model.
- Produces: stable application-facing command/result/port types for controller and services.

- [ ] **Step 1: Add command records**

Create `RolePermissionCommand.java`:

```java
package com.vandunxg.file_processing.auth.application.command;

import java.util.Set;

import com.vandunxg.common.models.enums.Action;
import com.vandunxg.file_processing.auth.domain.model.ResourceCode;

public record RolePermissionCommand(ResourceCode resourceCode, Set<Action> actions) {

  public RolePermissionCommand {
    actions = actions == null ? Set.of() : Set.copyOf(actions);
  }
}
```

Create `CreateRoleCommand.java`:

```java
package com.vandunxg.file_processing.auth.application.command;

import java.util.Set;
import java.util.UUID;

public record CreateRoleCommand(
  UUID actorId,
  String code,
  String name,
  String description,
  Set<RolePermissionCommand> permissions) {

  public CreateRoleCommand {
    permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
  }
}
```

Create `UpdateRoleCommand.java`:

```java
package com.vandunxg.file_processing.auth.application.command;

import java.util.Set;
import java.util.UUID;

public record UpdateRoleCommand(
  UUID actorId,
  UUID roleId,
  String code,
  String name,
  String description,
  Set<RolePermissionCommand> permissions) {

  public UpdateRoleCommand {
    permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
  }
}
```

Create `SetRoleInheritanceCommand.java`:

```java
package com.vandunxg.file_processing.auth.application.command;

import java.util.UUID;

public record SetRoleInheritanceCommand(UUID actorId, UUID roleId, UUID roleInheritedId) {}
```

Create `RoleActionCommand.java`:

```java
package com.vandunxg.file_processing.auth.application.command;

import java.util.UUID;

public record RoleActionCommand(UUID actorId, UUID roleId) {}
```

- [ ] **Step 2: Add permission catalog result records**

Create `PermissionResourceResult.java`:

```java
package com.vandunxg.file_processing.auth.application.result;

import com.vandunxg.file_processing.auth.domain.model.ResourceCode;

public record PermissionResourceResult(ResourceCode resourceCode, String resourceGroup) {}
```

Create `ResourcePermissionResult.java`:

```java
package com.vandunxg.file_processing.auth.application.result;

import java.util.List;

import com.vandunxg.common.models.enums.Action;
import com.vandunxg.file_processing.auth.domain.model.ResourceCode;

public record ResourcePermissionResult(
  ResourceCode resourceCode, String resourceGroup, List<Action> actions) {}
```

- [ ] **Step 3: Add inbound port interfaces**

Create `RoleManagementUseCase.java`:

```java
package com.vandunxg.file_processing.auth.application.port.in;

import java.util.UUID;

import com.vandunxg.file_processing.auth.application.command.CreateRoleCommand;
import com.vandunxg.file_processing.auth.application.command.RoleActionCommand;
import com.vandunxg.file_processing.auth.application.command.SetRoleInheritanceCommand;
import com.vandunxg.file_processing.auth.application.command.UpdateRoleCommand;
import com.vandunxg.file_processing.auth.domain.model.Role;

public interface RoleManagementUseCase {

  Role detail(UUID roleId);

  Role create(CreateRoleCommand command);

  Role update(UpdateRoleCommand command);

  Role setInheritance(SetRoleInheritanceCommand command);

  Role activate(RoleActionCommand command);

  Role inactivate(RoleActionCommand command);

  void delete(RoleActionCommand command);
}
```

Create `PermissionCatalogUseCase.java`:

```java
package com.vandunxg.file_processing.auth.application.port.in;

import java.util.List;

import com.vandunxg.file_processing.auth.application.result.PermissionResourceResult;
import com.vandunxg.file_processing.auth.application.result.ResourcePermissionResult;

public interface PermissionCatalogUseCase {

  List<PermissionResourceResult> resources();

  List<ResourcePermissionResult> permissions();
}
```

- [ ] **Step 4: Compile the new contracts**

Run: `./mvnw -DskipTests compile`

Expected: PASS after formatting imports with the project's formatter in the final task. If compile fails here, fix only missing imports or package names introduced in this task.

---

### Task 3: Move Service Boundaries To The New Ports

**Files:**
- Modify: `src/main/java/com/vandunxg/file_processing/auth/application/service/RoleManagementService.java`
- Modify: `src/main/java/com/vandunxg/file_processing/auth/application/service/PermissionCatalogService.java`
- Modify: `src/test/java/com/vandunxg/file_processing/auth/application/service/RoleManagementServiceTest.java`

**Interfaces:**
- Consumes: command/result/port types from Task 2.
- Produces: services that expose application use cases instead of controller-oriented primitive write inputs.

- [ ] **Step 1: Update `RoleManagementService` imports and implemented ports**

Add imports:

```java
import com.vandunxg.file_processing.auth.application.command.CreateRoleCommand;
import com.vandunxg.file_processing.auth.application.command.RoleActionCommand;
import com.vandunxg.file_processing.auth.application.command.RolePermissionCommand;
import com.vandunxg.file_processing.auth.application.command.SetRoleInheritanceCommand;
import com.vandunxg.file_processing.auth.application.command.UpdateRoleCommand;
import com.vandunxg.file_processing.auth.application.port.in.RoleManagementUseCase;
```

Change the class declaration:

```java
public class RoleManagementService implements SearchRolesUseCase, RoleManagementUseCase {
```

- [ ] **Step 2: Replace public mutation methods with command-based methods**

Replace the existing public `create`, `update`, and `setInheritance` primitive-argument methods with these command-based methods. Do not keep public primitive overloads:

```java
  @Override
  @Transactional
  public Role create(CreateRoleCommand command) {
    String normalizedCode =
        command.code() == null ? null : command.code().trim().toUpperCase(java.util.Locale.ROOT);
    if (normalizedCode == null || normalizedCode.isBlank()) {
      throw new IllegalArgumentException("Role code is required");
    }
    if (roleRepositoryPort.findByCode(normalizedCode).isPresent()) {
      throw new AuthDomainException(AuthErrorCode.ROLE_CODE_ALREADY_EXISTS);
    }
    Instant now = Instant.now(clock);
    Role saved = roleRepositoryPort.save(Role.create(normalizedCode, command.name(), command.description(), now));
    roleRepositoryPort.replacePermissions(
        saved.getId(), permissionsFor(saved.getId(), command.permissions()), now);
    publishAfterCommit(audit(command.actorId(), saved.getId(), OperationType.CREATE, now));
    return detail(saved.getId());
  }

  @Override
  @Transactional
  public Role update(UpdateRoleCommand command) {
    Role role = detail(command.roleId());
    String normalizedCode =
        command.code() == null ? null : command.code().trim().toUpperCase(java.util.Locale.ROOT);
    roleRepositoryPort
        .findByCode(normalizedCode)
        .filter(existing -> !existing.getId().equals(command.roleId()))
        .ifPresent(
            existing -> {
              throw new AuthDomainException(AuthErrorCode.ROLE_CODE_ALREADY_EXISTS);
            });
    try {
      role.update(normalizedCode, command.name(), command.description());
    } catch (IllegalStateException exception) {
      throw new AuthDomainException(AuthErrorCode.ROLE_IS_CONST);
    }
    Set<RolePermission> newPermissions = permissionsFor(command.roleId(), command.permissions());
    if ("ADMIN".equals(role.getCode()) && !isAdminPermissionSet(newPermissions)) {
      throw new AuthDomainException(AuthErrorCode.LAST_ACTIVE_ADMIN);
    }
    Instant now = Instant.now(clock);
    roleRepositoryPort.save(role);
    roleRepositoryPort.replacePermissions(command.roleId(), newPermissions, now);
    invalidateUsersFor(roleAndDescendants(command.roleId()));
    publishAfterCommit(audit(command.actorId(), command.roleId(), OperationType.ROLE_PERMISSION_UPDATED, now));
    return detail(command.roleId());
  }

  @Override
  @Transactional
  public Role setInheritance(SetRoleInheritanceCommand command) {
    Role child = detail(command.roleId());
    if (child.isConst()) {
      throw new AuthDomainException(AuthErrorCode.ROLE_IS_CONST);
    }
    Map<UUID, Role> roles = rolesById();
    if (command.roleInheritedId() != null && !roles.containsKey(command.roleInheritedId())) {
      throw new AuthDomainException(AuthErrorCode.ROLE_NOT_FOUND);
    }
    for (UUID cursor = command.roleInheritedId(); cursor != null; ) {
      if (command.roleId().equals(cursor)) {
        throw new AuthDomainException(AuthErrorCode.ROLE_INHERITANCE_CYCLE);
      }
      Role current = roles.get(cursor);
      cursor = current == null ? null : current.getRoleInheritedId();
    }
    child.setInheritedRole(command.roleInheritedId());
    Instant now = Instant.now(clock);
    roleRepositoryPort.save(child);
    invalidateUsersFor(roleAndDescendants(command.roleId()));
    publishAfterCommit(audit(command.actorId(), command.roleId(), OperationType.ROLE_INHERITANCE_UPDATED, now));
    return detail(command.roleId());
  }
```

- [ ] **Step 3: Replace simple state methods with command-based methods**

Use these method signatures and bodies:

```java
  @Override
  @Transactional
  public Role activate(RoleActionCommand command) {
    Role role = detail(command.roleId());
    if (role.isActive()) {
      return role;
    }
    role.activate();
    Instant now = Instant.now(clock);
    Role saved = roleRepositoryPort.save(role);
    invalidateUsersFor(roleAndDescendants(command.roleId()));
    publishAfterCommit(audit(command.actorId(), command.roleId(), OperationType.ACTIVATED, now));
    return saved;
  }

  @Override
  @Transactional
  public Role inactivate(RoleActionCommand command) {
    Role role = detail(command.roleId());
    if ("ADMIN".equals(role.getCode())) {
      throw new AuthDomainException(AuthErrorCode.LAST_ACTIVE_ADMIN);
    }
    if (!role.isActive()) {
      return role;
    }
    role.inactivate();
    Instant now = Instant.now(clock);
    Role saved = roleRepositoryPort.save(role);
    invalidateUsersFor(roleAndDescendants(command.roleId()));
    publishAfterCommit(audit(command.actorId(), command.roleId(), OperationType.DEACTIVATED, now));
    return saved;
  }

  @Override
  @Transactional
  public void delete(RoleActionCommand command) {
    Role role = detail(command.roleId());
    if (role.isConst()) {
      throw new AuthDomainException(AuthErrorCode.ROLE_IS_CONST);
    }
    if (role.isActive()) {
      throw new AuthDomainException(AuthErrorCode.ROLE_IS_ACTIVE);
    }
    if (!roleRepositoryPort.findActiveUserIdsByRoleIds(Set.of(command.roleId())).isEmpty()) {
      throw new AuthDomainException(AuthErrorCode.ROLE_STILL_ASSIGNED);
    }
    Instant now = Instant.now(clock);
    role.delete(now);
    roleRepositoryPort.save(role);
    publishAfterCommit(audit(command.actorId(), command.roleId(), OperationType.DELETE, now));
  }
```

- [ ] **Step 4: Update permission conversion helper**

Replace the old `PermissionSpec` helper with command input:

```java
  private Set<RolePermission> permissionsFor(UUID roleId, Set<RolePermissionCommand> permissions) {
    if (permissions == null) {
      return Set.of();
    }
    return permissions.stream()
        .filter(spec -> spec.resourceCode() != null && spec.actions() != null)
        .flatMap(
            spec ->
                spec.actions().stream()
                    .map(action -> RolePermission.grant(roleId, spec.resourceCode(), action)))
        .collect(Collectors.toSet());
  }
```

Delete the nested `PermissionSpec` record from `RoleManagementService`.

- [ ] **Step 5: Update `PermissionCatalogService` to return application results**

Replace the current class contents with:

```java
package com.vandunxg.file_processing.auth.application.service;

import java.util.Arrays;
import java.util.List;

import com.vandunxg.common.models.enums.Action;
import com.vandunxg.file_processing.auth.application.port.in.PermissionCatalogUseCase;
import com.vandunxg.file_processing.auth.application.result.PermissionResourceResult;
import com.vandunxg.file_processing.auth.application.result.ResourcePermissionResult;
import com.vandunxg.file_processing.auth.domain.model.ResourceCode;
import org.springframework.stereotype.Service;

@Service
public class PermissionCatalogService implements PermissionCatalogUseCase {

  @Override
  public List<ResourcePermissionResult> permissions() {
    return Arrays.stream(ResourceCode.values())
        .map(
            resource ->
                new ResourcePermissionResult(resource, resource.getGroup(), List.of(Action.values())))
        .toList();
  }

  @Override
  public List<PermissionResourceResult> resources() {
    return Arrays.stream(ResourceCode.values())
        .map(resource -> new PermissionResourceResult(resource, resource.getGroup()))
        .toList();
  }
}
```

- [ ] **Step 6: Update direct service tests to command inputs**

In `RoleManagementServiceTest.java`, add imports:

```java
import com.vandunxg.file_processing.auth.application.command.SetRoleInheritanceCommand;
import com.vandunxg.file_processing.auth.application.command.UpdateRoleCommand;
```

Replace the inheritance assertion call:

```java
assertThatThrownBy(
        () ->
            service.setInheritance(
                new SetRoleInheritanceCommand(UUID.randomUUID(), childId, parentId)))
    .isInstanceOf(AuthDomainException.class)
    .extracting("error")
    .isEqualTo(AuthErrorCode.ROLE_INHERITANCE_CYCLE);
```

Replace the update call:

```java
newService()
    .update(
        new UpdateRoleCommand(
            UUID.randomUUID(), roleId, "AUDITOR", "Auditor", null, java.util.Set.of()));
```

- [ ] **Step 7: Run service tests**

Run: `./mvnw -Dtest=RoleManagementServiceTest test`

Expected: PASS.

---

### Task 4: Move Role Web DTOs And Mapper Boundaries

**Files:**
- Create: `src/main/java/com/vandunxg/file_processing/auth/adapter/in/web/dto/request/RoleRequest.java`
- Create: `src/main/java/com/vandunxg/file_processing/auth/adapter/in/web/dto/request/RolePermissionRequest.java`
- Create: `src/main/java/com/vandunxg/file_processing/auth/adapter/in/web/dto/request/RoleInheritanceRequest.java`
- Create: `src/main/java/com/vandunxg/file_processing/auth/adapter/in/web/dto/response/ResourcePermissionResponse.java`
- Modify: `src/main/java/com/vandunxg/file_processing/auth/adapter/in/web/mapper/RoleWebMapper.java`

**Interfaces:**
- Consumes: command/result types from Task 2 and current `RoleResponse`.
- Produces: web-only request/response DTOs and mapper methods used by the controller.

- [ ] **Step 1: Add role request DTOs**

Create `RoleRequest.java`:

```java
package com.vandunxg.file_processing.auth.adapter.in.web.dto.request;

import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RoleRequest(
  @NotBlank @Pattern(regexp = "^[A-Za-z0-9_]+$") @Size(max = 50) String code,
  @NotBlank @Size(max = 100) String name,
  @Size(max = 1000) String description,
  Set<@Valid RolePermissionRequest> permissions) {

  public RoleRequest {
    permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
  }
}
```

Create `RolePermissionRequest.java`:

```java
package com.vandunxg.file_processing.auth.adapter.in.web.dto.request;

import java.util.Set;

import com.vandunxg.common.models.enums.Action;
import com.vandunxg.file_processing.auth.domain.model.ResourceCode;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public record RolePermissionRequest(
  @NotNull ResourceCode resourceCode, @NotEmpty Set<Action> actions) {}
```

Create `RoleInheritanceRequest.java`:

```java
package com.vandunxg.file_processing.auth.adapter.in.web.dto.request;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record RoleInheritanceRequest(@NotNull UUID roleId, UUID roleInheritedId) {}
```

- [ ] **Step 2: Add permission catalog response DTO**

Create `ResourcePermissionResponse.java`:

```java
package com.vandunxg.file_processing.auth.adapter.in.web.dto.response;

import java.util.List;

public record ResourcePermissionResponse(
  String resourceCode, String resourceGroup, List<String> actions) {}
```

- [ ] **Step 3: Update `RoleWebMapper` imports and methods**

Replace the mapper with this shape:

```java
package com.vandunxg.file_processing.auth.adapter.in.web.mapper;

import java.util.List;
import java.util.UUID;

import com.vandunxg.common.models.enums.Action;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.request.RoleInheritanceRequest;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.request.RoleRequest;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.request.RoleSearchRequest;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.response.ResourcePermissionResponse;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.response.RoleResponse;
import com.vandunxg.file_processing.auth.application.command.CreateRoleCommand;
import com.vandunxg.file_processing.auth.application.command.RolePermissionCommand;
import com.vandunxg.file_processing.auth.application.command.SetRoleInheritanceCommand;
import com.vandunxg.file_processing.auth.application.command.UpdateRoleCommand;
import com.vandunxg.file_processing.auth.application.query.RoleSearchQuery;
import com.vandunxg.file_processing.auth.application.result.PermissionResourceResult;
import com.vandunxg.file_processing.auth.application.result.ResourcePermissionResult;
import com.vandunxg.file_processing.auth.domain.model.ResourceCode;
import com.vandunxg.file_processing.auth.domain.model.Role;
import com.vandunxg.file_processing.auth.domain.model.RolePermission;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(
  componentModel = MappingConstants.ComponentModel.SPRING,
  unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface RoleWebMapper {

  RoleSearchQuery toQuery(RoleSearchRequest request);

  @Mapping(target = "actorId", source = "actorId")
  CreateRoleCommand toCreateCommand(RoleRequest request, UUID actorId);

  @Mapping(target = "actorId", source = "actorId")
  @Mapping(target = "roleId", source = "roleId")
  UpdateRoleCommand toUpdateCommand(RoleRequest request, UUID actorId, UUID roleId);

  @Mapping(target = "actorId", source = "actorId")
  SetRoleInheritanceCommand toCommand(RoleInheritanceRequest request, UUID actorId);

  RolePermissionCommand toCommand(
    com.vandunxg.file_processing.auth.adapter.in.web.dto.request.RolePermissionRequest request);

  @Mapping(target = "isConst", source = "const")
  @Mapping(target = "status", expression = "java(role.getStatus().name())")
  RoleResponse toResponse(Role role);

  List<RoleResponse> toResponse(List<Role> roles);

  ResourcePermissionResponse toResponse(ResourcePermissionResult result);

  List<ResourcePermissionResponse> toPermissionResponses(List<ResourcePermissionResult> results);

  default List<String> toResourceCodes(List<PermissionResourceResult> results) {
    return results.stream().map(result -> map(result.resourceCode())).toList();
  }

  default String map(ResourceCode resourceCode) {
    return resourceCode == null ? null : resourceCode.name();
  }

  default String map(Action action) {
    return action == null ? null : action.name();
  }

  default String toAuthority(RolePermission permission) {
    return permission.authority();
  }
}
```

- [ ] **Step 4: Compile mapper generation**

Run: `./mvnw -DskipTests compile`

Expected: PASS. If MapStruct reports an unmapped target, add an explicit `@Mapping` rather than moving mapping code into the controller.

---

### Task 5: Refactor `RoleManagementController`

**Files:**
- Modify: `src/main/java/com/vandunxg/file_processing/auth/adapter/in/web/RoleManagementController.java`

**Interfaces:**
- Consumes: web DTOs, mapper methods, inbound ports, and `SecurityUtils.authentication().getUserId()`.
- Produces: thin controller with no nested DTOs, no direct service dependencies, and `201 Created` for create.

- [ ] **Step 1: Replace service imports with DTOs, ports, and SecurityUtils**

The controller imports should include these application/web types and remove `Jwt`, `Objects`, `Collectors`, `PermissionCatalogService`, `RoleManagementService`, `ResourceCode`, and nested DTO support imports:

```java
import java.util.List;
import java.util.UUID;

import com.vandunxg.common.models.dto.response.PagingResponse;
import com.vandunxg.common.models.dto.response.Response;
import com.vandunxg.common.web.support.SecurityUtils;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.request.RoleInheritanceRequest;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.request.RoleRequest;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.request.RoleSearchRequest;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.response.ResourcePermissionResponse;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.response.RoleResponse;
import com.vandunxg.file_processing.auth.adapter.in.web.mapper.RoleWebMapper;
import com.vandunxg.file_processing.auth.application.command.RoleActionCommand;
import com.vandunxg.file_processing.auth.application.port.in.PermissionCatalogUseCase;
import com.vandunxg.file_processing.auth.application.port.in.RoleManagementUseCase;
import com.vandunxg.file_processing.auth.application.port.in.SearchRolesUseCase;
import com.vandunxg.file_processing.auth.application.query.RoleSearchQuery;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
```

- [ ] **Step 2: Replace injected concrete services with inbound ports**

Use these fields:

```java
  private final RoleWebMapper roleWebMapper;
  private final SearchRolesUseCase searchRolesUseCase;
  private final RoleManagementUseCase roleManagementUseCase;
  private final PermissionCatalogUseCase permissionCatalogUseCase;
```

- [ ] **Step 3: Update catalog and detail endpoints**

Use these endpoint bodies:

```java
  public Response<List<String>> resources() {
    return Response.of(roleWebMapper.toResourceCodes(permissionCatalogUseCase.resources()));
  }

  public Response<List<ResourcePermissionResponse>> permissions() {
    return Response.of(roleWebMapper.toPermissionResponses(permissionCatalogUseCase.permissions()));
  }

  public Response<RoleResponse> detail(@PathVariable UUID roleId) {
    return Response.of(roleWebMapper.toResponse(roleManagementUseCase.detail(roleId)));
  }
```

- [ ] **Step 4: Update create and update endpoints**

Use these method bodies and add `@ResponseStatus(HttpStatus.CREATED)` only to `create`:

```java
  @Operation(summary = "Create a mutable role", description = "Requires `role:create`.")
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasPermission(null, 'role:create')")
  public Response<RoleResponse> create(@Valid @RequestBody RoleRequest request) {
    return Response.of(
        roleWebMapper.toResponse(
            roleManagementUseCase.create(roleWebMapper.toCreateCommand(request, actorId()))));
  }

  @Operation(
    summary = "Update a role and replace its permission set",
    description = "Requires `role:update`.")
  @PostMapping("/{roleId}/update")
  @PreAuthorize("hasPermission(null, 'role:update')")
  public Response<RoleResponse> update(
    @PathVariable UUID roleId, @Valid @RequestBody RoleRequest request) {
    return Response.of(
        roleWebMapper.toResponse(
            roleManagementUseCase.update(roleWebMapper.toUpdateCommand(request, actorId(), roleId))));
  }
```

- [ ] **Step 5: Update inheritance, status, and delete endpoints**

Use these bodies:

```java
  public Response<RoleResponse> setInheritance(@Valid @RequestBody RoleInheritanceRequest request) {
    return Response.of(
        roleWebMapper.toResponse(
            roleManagementUseCase.setInheritance(roleWebMapper.toCommand(request, actorId()))));
  }

  public Response<RoleResponse> activate(@PathVariable UUID roleId) {
    return Response.of(
        roleWebMapper.toResponse(roleManagementUseCase.activate(new RoleActionCommand(actorId(), roleId))));
  }

  public Response<RoleResponse> inactivate(@PathVariable UUID roleId) {
    return Response.of(
        roleWebMapper.toResponse(roleManagementUseCase.inactivate(new RoleActionCommand(actorId(), roleId))));
  }

  public Response<Void> delete(@PathVariable UUID roleId) {
    roleManagementUseCase.delete(new RoleActionCommand(actorId(), roleId));
    return Response.of(null);
  }
```

- [ ] **Step 6: Add current-user helper and delete nested records/helpers**

At the bottom of the class, keep only this helper and remove the old `subject`, `permissions`, and nested request records:

```java
  private static UUID actorId() {
    return SecurityUtils.authentication().getUserId();
  }
```

- [ ] **Step 7: Run the controller integration test**

Run: `./mvnw -Dtest=RoleManagementControllerIT test`

Expected: PASS, including the new `201 Created` test and all existing list tests.

---

### Task 6: Final Formatting And Verification

**Files:**
- Modify only files already touched by Tasks 1-5 if formatting changes are needed.

**Interfaces:**
- Consumes: complete refactor.
- Produces: formatted, verified code ready for review.

- [ ] **Step 1: Apply formatting**

Run: `./mvnw spotless:apply`

Expected: PASS and only formatting changes in touched files.

- [ ] **Step 2: Run focused tests**

Run: `./mvnw -Dtest=RoleManagementControllerIT,RoleManagementServiceTest test`

Expected: PASS.

- [ ] **Step 3: Run full verification**

Run: `./mvnw verify`

Expected: PASS. If infrastructure-dependent tests fail because Docker/Testcontainers is unavailable, capture the exact failing command and error output.

- [ ] **Step 4: Check the worktree**

Run: `git status --short`

Expected: only the intended source, test, spec, and plan files are changed. Do not revert unrelated pre-existing changes.

---

## Self-Review

- Spec coverage: Tasks 2-5 cover ports, commands, DTO extraction, catalog result mapping, `SecurityUtils`, and `201 Created`; Task 1 and Task 6 cover verification.
- Placeholder scan: no deferred behavior, no unspecified edge cases, and no new framework/dependency work.
- Type consistency: controller uses `RoleManagementUseCase`, `PermissionCatalogUseCase`, `RoleActionCommand`, request DTOs, and mapper methods defined earlier in the plan.
