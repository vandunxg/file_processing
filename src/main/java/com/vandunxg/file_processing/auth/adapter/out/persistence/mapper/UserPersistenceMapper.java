package com.vandunxg.file_processing.auth.adapter.out.persistence.mapper;

import java.util.List;

import com.vandunxg.common.models.mapper.EntityMapper;
import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.UserEntity;
import com.vandunxg.file_processing.auth.domain.model.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.ERROR,
    unmappedSourcePolicy = ReportingPolicy.WARN)
public interface UserPersistenceMapper extends EntityMapper<User, UserEntity> {

  // roles have no JPA relation on UserEntity; UserPersistenceAdapter attaches them after mapping
  // via User.enrichRoles(...), joined separately against the user_role table.
  @Override
  @Mapping(target = "roles", ignore = true)
  User toDomain(UserEntity entity);

  @Override
  @Mapping(target = "createdAt", ignore = true) // audit-managed by JPA auditing listener
  @Mapping(target = "lastModifiedAt", ignore = true) // audit-managed by JPA auditing listener
  @Mapping(target = "createdBy", ignore = true) // audit-managed by JPA auditing listener
  @Mapping(target = "lastModifiedBy", ignore = true) // audit-managed by JPA auditing listener
  @Mapping(target = "lastFailedLoginAt", ignore = true) // domain doesn't track this yet
  @Mapping(target = "lastLoginAt", ignore = true) // domain doesn't track this yet
  UserEntity toEntity(User domain);

  @Override
  List<User> toDomain(List<UserEntity> entities);

  @Override
  List<UserEntity> toEntity(List<User> domains);
}
