package com.vandunxg.file_processing.auth.adapter.in.web;

import com.vandunxg.common.models.dto.response.PagingResponse;
import com.vandunxg.common.models.dto.response.Response;
import com.vandunxg.common.models.enums.Action;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.request.RoleSearchRequest;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.response.RoleResponse;
import com.vandunxg.file_processing.auth.adapter.in.web.mapper.RoleWebMapper;
import com.vandunxg.file_processing.auth.application.port.in.SearchRolesUseCase;
import com.vandunxg.file_processing.auth.application.query.RoleSearchQuery;
import com.vandunxg.file_processing.auth.application.service.PermissionCatalogService;
import com.vandunxg.file_processing.auth.application.service.RoleManagementService;
import com.vandunxg.file_processing.auth.application.service.RoleManagementService.PermissionSpec;
import com.vandunxg.file_processing.auth.domain.model.ResourceCode;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("${app.api.prefix}/${app.api.version}/roles")
@RequiredArgsConstructor
@Tag(
  name = "Admin roles",
  description = "Bearer access token required. `all:manage` satisfies every role permission.")
public class RoleManagementController {

  private final RoleWebMapper roleWebMapper;
  private final SearchRolesUseCase searchRolesUseCase;
  private final RoleManagementService roleManagementService;
  private final PermissionCatalogService permissionCatalogService;

  @Operation(summary = "List roles", description = "Requires `role:read` or `user:read`.")
  @GetMapping
  @PreAuthorize("hasPermission(null, 'role:read') or hasPermission(null, 'user:read')")
  public PagingResponse<RoleResponse> list(@Valid RoleSearchRequest request) {

    RoleSearchQuery query = roleWebMapper.toQuery(request);

    return new PagingResponse<>(
      searchRolesUseCase.search(query), roleWebMapper::toResponse);
  }

  @Operation(
    summary = "Read the permission resource catalog",
    description = "Requires `role:read`.")
  @GetMapping("/resources")
  @PreAuthorize("hasPermission(null, 'role:read')")
  public Response<List<ResourceCode>> resources() {
    return Response.of(permissionCatalogService.resources());
  }

  @Operation(summary = "Read the permission catalog", description = "Requires `role:read`.")
  @GetMapping("/permissions")
  @PreAuthorize("hasPermission(null, 'role:read')")
  public Response<List<PermissionCatalogService.ResourcePermission>> permissions() {
    return Response.of(permissionCatalogService.permissions());
  }

  @Operation(summary = "Read a role", description = "Requires `role:read`.")
  @GetMapping("/{roleId}")
  @PreAuthorize("hasPermission(null, 'role:read')")
  public Response<RoleResponse> detail(@PathVariable UUID roleId) {
    return Response.of(roleWebMapper.toResponse(roleManagementService.detail(roleId)));
  }

  @Operation(summary = "Create a mutable role", description = "Requires `role:create`.")
  @PostMapping
  @PreAuthorize("hasPermission(null, 'role:create')")
  public Response<RoleResponse> create(
    @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RoleRequest request) {
    return Response.of(
      roleWebMapper.toResponse(
        roleManagementService.create(
          subject(jwt),
          request.code(),
          request.name(),
          request.description(),
          permissions(request))));
  }

  @Operation(
    summary = "Update a role and replace its permission set",
    description = "Requires `role:update`.")
  @PostMapping("/{roleId}/update")
  @PreAuthorize("hasPermission(null, 'role:update')")
  public Response<RoleResponse> update(
    @AuthenticationPrincipal Jwt jwt,
    @PathVariable UUID roleId,
    @Valid @RequestBody RoleRequest request) {
    return Response.of(
      roleWebMapper.toResponse(
        roleManagementService.update(
          subject(jwt),
          roleId,
          request.code(),
          request.name(),
          request.description(),
          permissions(request))));
  }

  @Operation(summary = "Set or clear role inheritance", description = "Requires `role:update`.")
  @PostMapping("/inheritance")
  @PreAuthorize("hasPermission(null, 'role:update')")
  public Response<RoleResponse> setInheritance(
    @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody InheritanceRequest request) {
    return Response.of(
      roleWebMapper.toResponse(
        roleManagementService.setInheritance(
          subject(jwt), request.roleId(), request.roleInheritedId())));
  }

  @Operation(summary = "Activate a role", description = "Requires `role:update`.")
  @PostMapping("/{roleId}/active")
  @PreAuthorize("hasPermission(null, 'role:update')")
  public Response<RoleResponse> activate(
    @AuthenticationPrincipal Jwt jwt, @PathVariable UUID roleId) {
    return Response.of(roleWebMapper.toResponse(roleManagementService.activate(subject(jwt), roleId)));
  }

  @Operation(summary = "Inactivate a role", description = "Requires `role:update`.")
  @PostMapping("/{roleId}/inactive")
  @PreAuthorize("hasPermission(null, 'role:update')")
  public Response<RoleResponse> inactivate(
    @AuthenticationPrincipal Jwt jwt, @PathVariable UUID roleId) {
    return Response.of(roleWebMapper.toResponse(roleManagementService.inactivate(subject(jwt), roleId)));
  }

  @Operation(
    summary = "Delete an inactive, unassigned mutable role",
    description = "Requires `role:delete`.")
  @PostMapping("/{roleId}/delete")
  @PreAuthorize("hasPermission(null, 'role:delete')")
  public Response<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID roleId) {
    roleManagementService.delete(subject(jwt), roleId);
    return Response.of(null);
  }

  private static Set<PermissionSpec> permissions(RoleRequest request) {
    return request.permissions().stream()
      .map(permission -> new PermissionSpec(permission.resourceCode(), permission.actions()))
      .collect(Collectors.toSet());
  }

  private static UUID subject(Jwt jwt) {
    return UUID.fromString(Objects.requireNonNull(jwt.getSubject()));
  }

  public record RoleRequest(
    @NotBlank @Pattern(regexp = "^[A-Za-z0-9_]+$") @Size(max = 50) String code,
    @NotBlank @Size(max = 100) String name,
    @Size(max = 1000) String description,
    Set<@Valid PermissionRequest> permissions) {
    public RoleRequest {
      permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
    }
  }

  public record PermissionRequest(
    @NotNull ResourceCode resourceCode, @NotEmpty Set<Action> actions) {
  }

  public record InheritanceRequest(@NotNull UUID roleId, UUID roleInheritedId) {
  }
}
