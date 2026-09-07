package com.vandunxg.file_processing.auth.infrastructure.cache;

import com.vandunxg.file_processing.auth.domain.model.EmailVerificationToken;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/**
 * Maps the aggregate to and from the JSON stored in Redis. The token hash is the Redis key, so it
 * is passed back in explicitly instead of being duplicated inside the value.
 */
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.ERROR,
    unmappedSourcePolicy = ReportingPolicy.WARN)
interface EmailVerificationTokenRedisMapper {

  EmailVerificationTokenRedisPayload toPayload(EmailVerificationToken token);

  @Mapping(target = "id", source = "payload.id")
  @Mapping(target = "userId", source = "payload.userId")
  @Mapping(target = "issuedAt", source = "payload.issuedAt")
  @Mapping(target = "expiresAt", source = "payload.expiresAt")
  @Mapping(target = "ipAddressHash", source = "payload.ipAddressHash")
  @Mapping(target = "tokenHash", source = "tokenHash")
  @Mapping(target = "usedAt", ignore = true)
  @Mapping(target = "createdBy", ignore = true)
  @Mapping(target = "createdAt", ignore = true)
  @Mapping(target = "lastModifiedBy", ignore = true)
  @Mapping(target = "lastModifiedAt", ignore = true)
  EmailVerificationToken toDomain(EmailVerificationTokenRedisPayload payload, String tokenHash);
}
