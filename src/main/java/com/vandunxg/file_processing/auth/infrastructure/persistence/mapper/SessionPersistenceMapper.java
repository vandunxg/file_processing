package com.vandunxg.file_processing.auth.infrastructure.persistence.mapper;

import java.util.List;

import com.vandunxg.common.models.mapper.EntityMapper;
import com.vandunxg.file_processing.auth.domain.model.Session;
import com.vandunxg.file_processing.auth.infrastructure.persistence.entity.SessionEntity;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.ERROR,
    unmappedSourcePolicy = ReportingPolicy.WARN)
public interface SessionPersistenceMapper extends EntityMapper<Session, SessionEntity> {

  @Override
  Session toDomain(SessionEntity entity);

  @Override
  @BeanMapping(ignoreUnmappedSourceProperties = {"createdBy", "lastModifiedBy", "lastModifiedAt"})
  @Mapping(target = "createdBy", ignore = true)
  @Mapping(target = "lastModifiedBy", ignore = true)
  @Mapping(target = "lastModifiedAt", ignore = true)
  SessionEntity toEntity(Session domain);

  @Override
  List<Session> toDomain(List<SessionEntity> entities);

  @Override
  List<SessionEntity> toEntity(List<Session> domains);
}
