package com.vandunxg.file_processing.auth.adapter.out.persistence.mapper;

import java.util.List;

import com.vandunxg.common.models.mapper.EntityMapper;
import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.EmailVerificationTokenEntity;
import com.vandunxg.file_processing.auth.domain.model.EmailVerificationToken;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.ERROR,
    unmappedSourcePolicy = ReportingPolicy.WARN)
public interface EmailVerificationTokenPersistenceMapper
    extends EntityMapper<EmailVerificationToken, EmailVerificationTokenEntity> {

  // The entity has no audit columns, so createdAt/lastModifiedAt/createdBy/lastModifiedBy have no
  // source to map from; they simply stay unset on the domain object (nothing reads them for this
  // aggregate — see Global Constraints).
  @Override
  @Mapping(target = "createdAt", ignore = true)
  @Mapping(target = "lastModifiedAt", ignore = true)
  @Mapping(target = "createdBy", ignore = true)
  @Mapping(target = "lastModifiedBy", ignore = true)
  EmailVerificationToken toDomain(EmailVerificationTokenEntity entity);

  // The entity doesn't declare createdAt/etc. as targets at all (unmappedSourcePolicy = WARN
  // covers the extra source properties on the domain side), so no ignores are needed here.
  @Override
  EmailVerificationTokenEntity toEntity(EmailVerificationToken domain);

  @Override
  List<EmailVerificationToken> toDomain(List<EmailVerificationTokenEntity> entities);

  @Override
  List<EmailVerificationTokenEntity> toEntity(List<EmailVerificationToken> domains);
}
