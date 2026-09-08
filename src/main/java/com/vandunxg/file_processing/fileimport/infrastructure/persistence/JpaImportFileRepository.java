package com.vandunxg.file_processing.fileimport.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import com.vandunxg.file_processing.fileimport.domain.ImportFileRepository;
import com.vandunxg.file_processing.fileimport.domain.model.FileImport;
import com.vandunxg.file_processing.fileimport.infrastructure.persistence.mapper.ImportFilePersistenceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class JpaImportFileRepository implements ImportFileRepository {

  private final ImportFileEntityRepository repository;
  private final ImportFilePersistenceMapper mapper;

  @Override
  public FileImport save(FileImport fileImport) {
    return mapper.toDomain(repository.saveAndFlush(mapper.toEntity(fileImport)));
  }

  @Override
  public Optional<FileImport> findByIdAndOwnerId(UUID id, UUID ownerId) {
    return repository.findByIdAndOwnerId(id, ownerId).map(mapper::toDomain);
  }
}
