package com.vandunxg.file_processing.fileimport.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import com.vandunxg.file_processing.fileimport.domain.ImportFileRepository;
import com.vandunxg.file_processing.fileimport.domain.model.ImportFile;
import com.vandunxg.file_processing.fileimport.infrastructure.persistence.mapper.ImportFilePersistenceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

/**
 * The file table predates the aggregate and still carries legacy processing columns, so the
 * aggregate keeps its own shape and this repository maps between the two.
 */
@Repository
@RequiredArgsConstructor
public class JpaImportFileRepository implements ImportFileRepository {

  private final ImportFileEntityRepository entityRepository;
  private final ImportFilePersistenceMapper mapper;

  @Override
  public ImportFile save(ImportFile importFile) {
    return mapper.toDomain(entityRepository.saveAndFlush(mapper.toNewEntity(importFile)));
  }

  @Override
  public Optional<ImportFile> findById(UUID id) {
    return entityRepository.findByIdAndDeletedAtIsNull(id).map(mapper::toDomain);
  }

  @Override
  public Optional<ImportFile> findByIdAndOwnerId(UUID id, UUID ownerId) {
    return entityRepository.findByIdAndOwnerIdAndDeletedAtIsNull(id, ownerId).map(mapper::toDomain);
  }
}
