package com.vandunxg.file_processing.auth.adapter.in.web;

import com.vandunxg.common.models.dto.response.PagingResponse;
import com.vandunxg.common.models.dto.response.Response;
import com.vandunxg.common.models.validator.ValidatePaging;
import com.vandunxg.common.web.support.SecurityUtils;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.request.RoleInheritanceRequest;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.request.RoleRequest;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.request.RoleSearchRequest;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.response.ResourcePermissionResponse;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.response.RoleResponse;
import com.vandunxg.file_processing.auth.adapter.in.web.mapper.RoleWebMapper;
import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.RoleEntity;
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
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("${app.api.prefix}/${app.api.version}/roles")
@RequiredArgsConstructor
@Tag(
  name = "Admin roles",
  description = "Bearer access token required. `all:manage` satisfies every role permission.")
public class RoleManagementController {

  private final RoleWebMapper roleWebMapper;
  private final SearchRolesUseCase searchRolesUseCase;
  private final RoleManagementUseCase roleManagementUseCase;
  private final PermissionCatalogUseCase permissionCatalogUseCase;

  @Operation(summary = "List roles", description = "Requires `role:read` or `user:read`.")
  @GetMapping
  @PreAuthorize("hasPermission(null, 'role:read') or hasPermission(null, 'user:read')")
  public PagingResponse<RoleResponse> list(
    @ValidatePaging(sortModel = RoleEntity.class) RoleSearchRequest request) {

    RoleSearchQuery query = roleWebMapper.toQuery(request);

    return new PagingResponse<>(searchRolesUseCase.search(query), roleWebMapper::toResponse);
  }

  @Operation(
    summary = "Read the permission resource catalog",
    description = "Requires `role:read`.")
  @GetMapping("/resources")
  @PreAuthorize("hasPermission(null, 'role:read')")
  public Response<List<String>> resources() {
    return Response.of(roleWebMapper.toResourceCodes(permissionCatalogUseCase.resources()));
  }

  @Operation(summary = "Read the permission catalog", description = "Requires `role:read`.")
  @GetMapping("/permissions")
  @PreAuthorize("hasPermission(null, 'role:read')")
  public Response<List<ResourcePermissionResponse>> permissions() {
    return Response.of(roleWebMapper.toPermissionResponses(permissionCatalogUseCase.permissions()));
  }

  @Operation(summary = "Read a role", description = "Requires `role:read`.")
  @GetMapping("/{roleId}")
  @PreAuthorize("hasPermission(null, 'role:read')")
  public Response<RoleResponse> detail(@PathVariable UUID roleId) {
    return Response.of(roleWebMapper.toResponse(roleManagementUseCase.detail(roleId)));
  }

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
        roleManagementUseCase.update(
          roleWebMapper.toUpdateCommand(request, actorId(), roleId))));
  }

  @Operation(summary = "Set or clear role inheritance", description = "Requires `role:update`.")
  @PostMapping("/inheritance")
  @PreAuthorize("hasPermission(null, 'role:update')")
  public Response<RoleResponse> setInheritance(@Valid @RequestBody RoleInheritanceRequest request) {
    return Response.of(
      roleWebMapper.toResponse(
        roleManagementUseCase.setInheritance(roleWebMapper.toCommand(request, actorId()))));
  }

  @Operation(summary = "Activate a role", description = "Requires `role:update`.")
  @PostMapping("/{roleId}/active")
  @PreAuthorize("hasPermission(null, 'role:update')")
  public Response<RoleResponse> activate(@PathVariable UUID roleId) {
    return Response.of(
      roleWebMapper.toResponse(
        roleManagementUseCase.activate(new RoleActionCommand(actorId(), roleId))));
  }

  @Operation(summary = "Inactivate a role", description = "Requires `role:update`.")
  @PostMapping("/{roleId}/inactive")
  @PreAuthorize("hasPermission(null, 'role:update')")
  public Response<RoleResponse> inactivate(@PathVariable UUID roleId) {
    return Response.of(
      roleWebMapper.toResponse(
        roleManagementUseCase.inactivate(new RoleActionCommand(actorId(), roleId))));
  }

  @Operation(
    summary = "Delete an inactive, unassigned mutable role",
    description = "Requires `role:delete`.")
  @PostMapping("/{roleId}/delete")
  @PreAuthorize("hasPermission(null, 'role:delete')")
  public Response<Boolean> delete(@PathVariable UUID roleId) {
    roleManagementUseCase.delete(new RoleActionCommand(actorId(), roleId));
    return Response.of(Boolean.TRUE);
  }

  private static UUID actorId() {
    return SecurityUtils.authentication().getUserId();
  }
}
