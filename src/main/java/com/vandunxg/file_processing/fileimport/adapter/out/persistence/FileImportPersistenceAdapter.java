package com.vandunxg.file_processing.fileimport.adapter.out.persistence;

import com.vandunxg.file_processing.fileimport.adapter.out.persistence.entity.JpaFileImportRepository;
import com.vandunxg.file_processing.fileimport.adapter.out.persistence.mapper.ImportFilePersistenceMapper;
import com.vandunxg.file_processing.fileimport.application.port.out.FileImportRepositoryPort;
import com.vandunxg.file_processing.fileimport.domain.model.FileImport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class FileImportPersistenceAdapter implements FileImportRepositoryPort {

  private final JpaFileImportRepository repository;
  private final ImportFilePersistenceMapper mapper;

  @Override
  public FileImport save(FileImport fileImport) {
    return mapper.toDomain(repository.saveAndFlush(mapper.toEntity(fileImport)));
  }
}
