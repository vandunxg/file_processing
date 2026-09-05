package com.vandunxg.file_processing.auth.infrastructure.persistence.mapper;

import java.util.List;

import com.vandunxg.common.models.mapper.EntityMapper;
import com.vandunxg.file_processing.auth.domain.model.PasswordResetToken;
import com.vandunxg.file_processing.auth.infrastructure.persistence.entity.PasswordResetTokenEntity;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.ERROR,
    unmappedSourcePolicy = ReportingPolicy.WARN)
public interface PasswordResetTokenPersistenceMapper
    extends EntityMapper<PasswordResetToken, PasswordResetTokenEntity> {

  @Override
  PasswordResetToken toDomain(PasswordResetTokenEntity entity);

  @Override
  PasswordResetTokenEntity toEntity(PasswordResetToken domain);

  @Override
  List<PasswordResetToken> toDomain(List<PasswordResetTokenEntity> entities);

  @Override
  List<PasswordResetTokenEntity> toEntity(List<PasswordResetToken> domains);
}
