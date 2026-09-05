package com.vandunxg.file_processing.auth.api.mapper;

import java.util.List;

import com.vandunxg.file_processing.auth.api.UserManagementController.UserResponse;
import com.vandunxg.file_processing.auth.api.dto.request.UserSearchRequest;
import com.vandunxg.file_processing.auth.application.query.UserSearchQuery;
import com.vandunxg.file_processing.auth.domain.model.Role;
import com.vandunxg.file_processing.auth.domain.model.User;
import com.vandunxg.file_processing.auth.domain.model.UserStatus;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface UserWebMapper {

  UserSearchQuery toQuery(UserSearchRequest request);

  @Mapping(target = "status", source = "status")
  UserResponse toResponse(User user);

  List<UserResponse> toResponse(List<User> users);

  default String map(UserStatus status) {
    return status == null ? null : status.name();
  }

  default String map(Role role) {
    return role == null ? null : role.getCode();
  }
}
