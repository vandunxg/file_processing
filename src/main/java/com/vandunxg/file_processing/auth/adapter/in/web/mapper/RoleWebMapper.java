package com.vandunxg.file_processing.auth.adapter.in.web.mapper;

import java.util.List;

import com.vandunxg.file_processing.auth.adapter.in.web.dto.request.RoleSearchRequest;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.response.RoleResponse;
import com.vandunxg.file_processing.auth.application.query.RoleSearchQuery;
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

  @Mapping(target = "isConst", source = "const")
  @Mapping(target = "status", expression = "java(role.getStatus().name())")
  RoleResponse toResponse(Role role);

  List<RoleResponse> toResponse(List<Role> roles);

  default String toAuthority(RolePermission permission) {
    return permission.authority();
  }
}
