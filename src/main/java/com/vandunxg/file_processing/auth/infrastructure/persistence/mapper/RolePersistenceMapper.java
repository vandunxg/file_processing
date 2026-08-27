package com.vandunxg.file_processing.auth.infrastructure.persistence.mapper;

import java.util.List;

import com.vandunxg.common.models.mapper.EntityMapper;
import com.vandunxg.file_processing.auth.domain.model.Role;
import com.vandunxg.file_processing.auth.infrastructure.persistence.entity.RoleEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.ERROR,
    unmappedSourcePolicy = ReportingPolicy.WARN)
public interface RolePersistenceMapper extends EntityMapper<Role, RoleEntity> {

  @Override
  @Mapping(target = "isConst", source = "const")
  @Mapping(target = "permissions", ignore = true)
  Role toDomain(RoleEntity entity);

  @Override
  @Mapping(target = "const", source = "const")
  @Mapping(target = "createdAt", ignore = true)
  @Mapping(target = "lastModifiedAt", ignore = true)
  @Mapping(target = "createdBy", ignore = true)
  @Mapping(target = "lastModifiedBy", ignore = true)
  RoleEntity toEntity(Role domain);

  @Override
  List<Role> toDomain(List<RoleEntity> entities);

  @Override
  List<RoleEntity> toEntity(List<Role> domains);
}
