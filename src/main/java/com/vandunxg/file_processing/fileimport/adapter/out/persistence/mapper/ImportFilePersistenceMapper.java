package com.vandunxg.file_processing.fileimport.adapter.out.persistence.mapper;

import java.util.List;

import com.vandunxg.common.models.mapper.EntityMapper;
import com.vandunxg.file_processing.fileimport.adapter.out.persistence.entity.ImportFileEntity;
import com.vandunxg.file_processing.fileimport.domain.model.FileImport;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.ERROR,
    unmappedSourcePolicy = ReportingPolicy.WARN)
public interface ImportFilePersistenceMapper extends EntityMapper<FileImport, ImportFileEntity> {

  @Override
  FileImport toDomain(ImportFileEntity entity);

  @Override
  @Mapping(target = "createdAt", ignore = true)
  @Mapping(target = "lastModifiedAt", ignore = true)
  @Mapping(target = "createdBy", ignore = true)
  @Mapping(target = "lastModifiedBy", ignore = true)
  ImportFileEntity toEntity(FileImport domain);

  @Override
  List<FileImport> toDomain(List<ImportFileEntity> entities);

  @Override
  List<ImportFileEntity> toEntity(List<FileImport> domains);
}
