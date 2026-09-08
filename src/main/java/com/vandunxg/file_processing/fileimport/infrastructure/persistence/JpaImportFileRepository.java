package com.vandunxg.file_processing.fileimport.infrastructure.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.vandunxg.file_processing.fileimport.domain.ImportFileRepository;
import com.vandunxg.file_processing.fileimport.domain.model.FileChecksum;
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
  public Optional<ImportFile> findByOwnerIdAndChecksum(UUID ownerId, FileChecksum checksum) {
    return entityRepository
        .findByOwnerIdAndChecksumSha256AndDeletedAtIsNull(ownerId, checksum.value())
        .map(mapper::toDomain);
  }

  @Override
  public Optional<ImportFile> findByIdAndOwnerId(UUID id, UUID ownerId) {
    return entityRepository.findByIdAndOwnerIdAndDeletedAtIsNull(id, ownerId).map(mapper::toDomain);
  }

  @Override
  public List<ImportFile> findAllByIds(Collection<UUID> ids) {
    if (ids.isEmpty()) {
      return List.of();
    }
    return entityRepository.findAllByIdInAndDeletedAtIsNull(ids).stream()
        .map(mapper::toDomain)
        .toList();
  }

  @Override
  public List<ImportFile> findExpired(Instant retentionBefore) {
    return entityRepository
        .findByRetentionDeadlineBeforeAndDeletedAtIsNull(retentionBefore)
        .stream()
        .map(mapper::toDomain)
        .toList();
  }
}
