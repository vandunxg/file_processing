package com.vandunxg.file_processing.fileimport.infrastructure.persistence;

import java.util.Optional;
import java.util.UUID;

import com.vandunxg.file_processing.fileimport.infrastructure.persistence.entity.ImportFileEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ImportFileEntityRepository extends JpaRepository<ImportFileEntity, UUID> {

  Optional<ImportFileEntity> findByIdAndOwnerIdAndDeletedAtIsNull(UUID id, UUID ownerId);

  Optional<ImportFileEntity> findByIdAndDeletedAtIsNull(UUID id);
}
