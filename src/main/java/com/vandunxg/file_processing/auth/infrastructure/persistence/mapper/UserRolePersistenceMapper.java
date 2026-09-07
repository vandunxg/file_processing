package com.vandunxg.file_processing.auth.infrastructure.persistence.mapper;

import java.util.List;

import com.vandunxg.common.models.mapper.EntityMapper;
import com.vandunxg.file_processing.auth.domain.model.UserRole;
import com.vandunxg.file_processing.auth.infrastructure.persistence.entity.UserRoleEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.ERROR,
    unmappedSourcePolicy = ReportingPolicy.WARN)
public interface UserRolePersistenceMapper extends EntityMapper<UserRole, UserRoleEntity> {

  @Override
  UserRole toDomain(UserRoleEntity entity);

  @Override
  @Mapping(target = "createdAt", ignore = true) // audit-managed by JPA auditing listener
  @Mapping(target = "lastModifiedAt", ignore = true) // audit-managed by JPA auditing listener
  @Mapping(target = "createdBy", ignore = true) // audit-managed by JPA auditing listener
  @Mapping(target = "lastModifiedBy", ignore = true) // audit-managed by JPA auditing listener
  UserRoleEntity toEntity(UserRole domain);

  @Override
  List<UserRole> toDomain(List<UserRoleEntity> entities);

  @Override
  List<UserRoleEntity> toEntity(List<UserRole> domains);
}
