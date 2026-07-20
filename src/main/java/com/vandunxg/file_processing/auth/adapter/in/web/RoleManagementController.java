package com.vandunxg.file_processing.auth.adapter.in.web;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.vandunxg.common.models.dto.response.Response;
import com.vandunxg.common.models.enums.Action;
import com.vandunxg.file_processing.auth.application.service.PermissionCatalogService;
import com.vandunxg.file_processing.auth.application.service.RoleManagementService;
import com.vandunxg.file_processing.auth.application.service.RoleManagementService.PermissionSpec;
import com.vandunxg.file_processing.auth.domain.model.ResourceCode;
import com.vandunxg.file_processing.auth.domain.model.Role;
import com.vandunxg.file_processing.auth.domain.model.RolePermission;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${app.api.prefix}/${app.api.version}/roles")
@RequiredArgsConstructor
public class RoleManagementController {

  private final RoleManagementService roleManagementService;
  private final PermissionCatalogService permissionCatalogService;

  @Operation(summary = "List roles")
  @GetMapping
  @PreAuthorize("hasPermission(null, 'role:read') or hasPermission(null, 'user:read')")
  public Response<List<RoleResponse>> list() {
    return Response.of(roleManagementService.list().stream().map(RoleResponse::from).toList());
  }

  @Operation(summary = "Read the permission resource catalog")
  @GetMapping("/resources")
  @PreAuthorize("hasPermission(null, 'role:read')")
  public Response<List<ResourceCode>> resources() {
    return Response.of(permissionCatalogService.resources());
  }

  @Operation(summary = "Read the permission catalog")
  @GetMapping("/permissions")
  @PreAuthorize("hasPermission(null, 'role:read')")
  public Response<List<PermissionCatalogService.ResourcePermission>> permissions() {
    return Response.of(permissionCatalogService.permissions());
  }

  @Operation(summary = "Read a role")
  @GetMapping("/{roleId}")
  @PreAuthorize("hasPermission(null, 'role:read')")
  public Response<RoleResponse> detail(@PathVariable UUID roleId) {
    return Response.of(RoleResponse.from(roleManagementService.detail(roleId)));
  }

  @Operation(summary = "Create a mutable role")
  @PostMapping
  @PreAuthorize("hasPermission(null, 'role:create')")
  public Response<RoleResponse> create(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody RoleRequest request) {
    return Response.of(
        RoleResponse.from(
            roleManagementService.create(
                subject(jwt),
                request.code(),
                request.name(),
                request.description(),
                permissions(request))));
  }

  @Operation(summary = "Update a role and replace its permission set")
  @PostMapping("/{roleId}/update")
  @PreAuthorize("hasPermission(null, 'role:update')")
  public Response<RoleResponse> update(
      @AuthenticationPrincipal Jwt jwt,
      @PathVariable UUID roleId,
      @Valid @RequestBody RoleRequest request) {
    return Response.of(
        RoleResponse.from(
            roleManagementService.update(
                subject(jwt),
                roleId,
                request.code(),
                request.name(),
                request.description(),
                permissions(request))));
  }

  @Operation(summary = "Set or clear role inheritance")
  @PostMapping("/inheritance")
  @PreAuthorize("hasPermission(null, 'role:update')")
  public Response<RoleResponse> setInheritance(
      @AuthenticationPrincipal Jwt jwt, @Valid @RequestBody InheritanceRequest request) {
    return Response.of(
        RoleResponse.from(
            roleManagementService.setInheritance(
                subject(jwt), request.roleId(), request.roleInheritedId())));
  }

  @Operation(summary = "Activate a role")
  @PostMapping("/{roleId}/active")
  @PreAuthorize("hasPermission(null, 'role:update')")
  public Response<RoleResponse> activate(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID roleId) {
    return Response.of(RoleResponse.from(roleManagementService.activate(subject(jwt), roleId)));
  }

  @Operation(summary = "Inactivate a role")
  @PostMapping("/{roleId}/inactive")
  @PreAuthorize("hasPermission(null, 'role:update')")
  public Response<RoleResponse> inactivate(
      @AuthenticationPrincipal Jwt jwt, @PathVariable UUID roleId) {
    return Response.of(RoleResponse.from(roleManagementService.inactivate(subject(jwt), roleId)));
  }

  @Operation(summary = "Delete an inactive, unassigned mutable role")
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
    return UUID.fromString(jwt.getSubject());
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
      @NotNull ResourceCode resourceCode, @NotEmpty Set<Action> actions) {}

  public record InheritanceRequest(@NotNull UUID roleId, UUID roleInheritedId) {}

  public record RoleResponse(
      UUID id,
      String code,
      String name,
      String description,
      boolean isConst,
      String status,
      UUID roleInheritedId,
      Set<String> permissions) {
    private static RoleResponse from(Role role) {
      return new RoleResponse(
          role.getId(),
          role.getCode(),
          role.getName(),
          role.getDescription(),
          role.isConst(),
          role.getStatus().name(),
          role.getRoleInheritedId(),
          role.getPermissions().stream()
              .map(RolePermission::authority)
              .collect(Collectors.toSet()));
    }
  }
}
