package com.vandunxg.file_processing.fileimport.infrastructure.persistence.mapper;

import com.vandunxg.file_processing.fileimport.domain.model.FileChecksum;
import com.vandunxg.file_processing.fileimport.domain.model.ImportFile;
import com.vandunxg.file_processing.fileimport.infrastructure.persistence.entity.ImportFileEntity;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/**
 * Maps between the immutable file aggregate and its legacy table.
 *
 * <p>The aggregate exposes no public constructor or builder, so nothing outside {@code register}
 * and {@code reconstitute} can produce one that skips its invariants. Rebuilding therefore calls
 * {@code reconstitute} explicitly instead of being generated; because that factory takes every
 * persisted field, adding a field to the aggregate breaks this call site until the entity carries
 * it too, which is the same protection {@code unmappedTargetPolicy = ERROR} gives the other
 * direction.
 */
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.ERROR,
    unmappedSourcePolicy = ReportingPolicy.WARN)
public abstract class ImportFilePersistenceMapper {

  public ImportFile toDomain(ImportFileEntity entity) {
    if (entity == null) {
      return null;
    }
    return ImportFile.reconstitute(
        entity.getId(),
        entity.getOwnerId(),
        entity.getOriginalFilename(),
        entity.getStorageKey(),
        FileChecksum.of(entity.getChecksumSha256()),
        entity.getSizeBytes(),
        entity.getDetectedContentType(),
        entity.getRetentionDeadline(),
        entity.getBucket(),
        entity.getStorageProvider(),
        entity.getVersion(),
        entity.getCreatedAt(),
        entity.getLastModifiedAt());
  }

  @Mapping(target = "checksumSha256", source = "checksum.value")
  @Mapping(target = "deletedAt", ignore = true)
  @Mapping(target = "createdAt", ignore = true)
  @Mapping(target = "lastModifiedAt", ignore = true)
  @Mapping(target = "createdBy", ignore = true)
  @Mapping(target = "lastModifiedBy", ignore = true)
  public abstract ImportFileEntity toNewEntity(ImportFile domain);
}
