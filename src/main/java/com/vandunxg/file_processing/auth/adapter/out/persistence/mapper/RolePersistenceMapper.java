package com.vandunxg.file_processing.auth.adapter.out.persistence.mapper;

import java.util.List;

import com.vandunxg.common.models.mapper.EntityMapper;
import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.RoleEntity;
import com.vandunxg.file_processing.auth.domain.model.Role;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.ERROR,
    unmappedSourcePolicy = ReportingPolicy.WARN)
public interface RolePersistenceMapper extends EntityMapper<Role, RoleEntity> {

  // roleInheritedId, name, description, isConst and version have no domain field to receive
  // them; they are simply left unmapped (unmappedSourcePolicy = WARN, not an error).
  @Override
  Role toDomain(RoleEntity entity);

  // This delivery only reads roles, never writes one (RoleRepositoryPort is read-only), so this
  // method is never called in practice; ignores below keep the build green and give the entity
  // safe defaults (see RoleEntity field initializers) if it is ever invoked.
  @Override
  @Mapping(target = "roleInheritedId", ignore = true)
  @Mapping(target = "name", ignore = true)
  @Mapping(target = "description", ignore = true)
  @Mapping(target = "const", ignore = true)
  @Mapping(target = "version", ignore = true)
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
