package com.vandunxg.file_processing.fileimport.adapter.out.persistence.entity;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaFileImportRepository extends JpaRepository<ImportFileEntity, UUID> {

  Optional<ImportFileEntity> findByIdAndOwnerId(UUID id, UUID ownerId);
}
