package com.vandunxg.file_processing.fileimport.domain;

import java.util.Optional;
import java.util.UUID;

import com.vandunxg.file_processing.fileimport.domain.model.FileImport;

public interface ImportFileRepository {

  FileImport save(FileImport fileImport);

  Optional<FileImport> findByIdAndOwnerId(UUID id, UUID ownerId);
}
