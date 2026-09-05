package com.vandunxg.file_processing.auth.application.mapper;

import com.vandunxg.file_processing.auth.application.result.MeResult;
import com.vandunxg.file_processing.auth.application.result.RegisterResult;
import com.vandunxg.file_processing.auth.domain.model.Role;
import com.vandunxg.file_processing.auth.domain.model.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/** Maps the {@code User} aggregate onto the application results the api layer consumes. */
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.ERROR,
    unmappedSourcePolicy = ReportingPolicy.WARN)
public interface UserResultMapper {

  @Mapping(target = "userId", source = "id")
  MeResult toMeResult(User user);

  RegisterResult toRegisterResult(User user);

  default String map(Role role) {
    return role == null ? null : role.getCode();
  }
}
