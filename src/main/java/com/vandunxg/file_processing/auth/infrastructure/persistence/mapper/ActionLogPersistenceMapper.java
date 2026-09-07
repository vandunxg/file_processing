package com.vandunxg.file_processing.auth.infrastructure.persistence.mapper;

import java.util.List;

import com.vandunxg.common.models.mapper.EntityMapper;
import com.vandunxg.file_processing.auth.domain.model.ActionLog;
import com.vandunxg.file_processing.auth.infrastructure.persistence.entity.ActionLogEntity;
import org.jspecify.annotations.NullMarked;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@NullMarked
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.ERROR,
    unmappedSourcePolicy = ReportingPolicy.WARN)
public interface ActionLogPersistenceMapper extends EntityMapper<ActionLog, ActionLogEntity> {

  @Override
  ActionLog toDomain(ActionLogEntity entity);

  @Override
  @Mapping(target = "createdAt", ignore = true)
  @Mapping(target = "lastModifiedAt", ignore = true)
  @Mapping(target = "createdBy", ignore = true)
  @Mapping(target = "lastModifiedBy", ignore = true)
  ActionLogEntity toEntity(ActionLog domain);

  @Override
  List<ActionLog> toDomain(List<ActionLogEntity> entities);

  @Override
  List<ActionLogEntity> toEntity(List<ActionLog> domains);
}
