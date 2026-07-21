package com.vandunxg.file_processing.auth.adapter.in.web.mapper;

import java.util.List;
import java.util.UUID;

import com.vandunxg.common.models.enums.Action;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.request.RoleInheritanceRequest;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.request.RolePermissionRequest;
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

  RolePermissionCommand toCommand(RolePermissionRequest request);

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
