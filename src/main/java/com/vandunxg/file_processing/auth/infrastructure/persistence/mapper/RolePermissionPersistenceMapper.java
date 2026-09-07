package com.vandunxg.file_processing.auth.infrastructure.persistence.mapper;

import java.util.List;

import com.vandunxg.common.models.mapper.EntityMapper;
import com.vandunxg.file_processing.auth.domain.model.RolePermission;
import com.vandunxg.file_processing.auth.infrastructure.persistence.entity.RolePermissionEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.ERROR,
    unmappedSourcePolicy = ReportingPolicy.WARN)
public interface RolePermissionPersistenceMapper
    extends EntityMapper<RolePermission, RolePermissionEntity> {

  @Override
  RolePermission toDomain(RolePermissionEntity entity);

  @Override
  @Mapping(target = "createdAt", ignore = true)
  @Mapping(target = "lastModifiedAt", ignore = true)
  @Mapping(target = "createdBy", ignore = true)
  @Mapping(target = "lastModifiedBy", ignore = true)
  RolePermissionEntity toEntity(RolePermission domain);

  @Override
  List<RolePermission> toDomain(List<RolePermissionEntity> entities);

  @Override
  List<RolePermissionEntity> toEntity(List<RolePermission> domains);
}
