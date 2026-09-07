package com.vandunxg.file_processing.auth.infrastructure.persistence.mapper;

import java.util.List;

import com.vandunxg.common.models.mapper.EntityMapper;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import com.vandunxg.file_processing.auth.infrastructure.persistence.entity.AuditLogEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.ERROR,
    unmappedSourcePolicy = ReportingPolicy.WARN)
public interface AuditLogPersistenceMapper extends EntityMapper<AuditLog, AuditLogEntity> {

  @Override
  AuditLog toDomain(AuditLogEntity entity);

  // write-only adapter in this delivery (AuditLogRepository#record only ever calls toEntity); audit
  // columns are populated by the JPA auditing listener on insert.
  @Override
  @Mapping(target = "createdAt", ignore = true)
  @Mapping(target = "lastModifiedAt", ignore = true)
  @Mapping(target = "createdBy", ignore = true)
  @Mapping(target = "lastModifiedBy", ignore = true)
  AuditLogEntity toEntity(AuditLog domain);

  @Override
  List<AuditLog> toDomain(List<AuditLogEntity> entities);

  @Override
  List<AuditLogEntity> toEntity(List<AuditLog> domains);
}
