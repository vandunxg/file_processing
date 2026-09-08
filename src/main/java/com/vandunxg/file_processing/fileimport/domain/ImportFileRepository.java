package com.vandunxg.file_processing.fileimport.domain;

import java.util.Optional;
import java.util.UUID;

import com.vandunxg.file_processing.fileimport.domain.model.FileChecksum;
import com.vandunxg.file_processing.fileimport.domain.model.ImportFile;

/** Aggregate repository for the immutable metadata of an accepted import file. */
public interface ImportFileRepository {

  ImportFile save(ImportFile importFile);

  Optional<ImportFile> findById(UUID id);

  /**
   * Loads a file only when the caller owns it, so asking for someone else's file is
   * indistinguishable from asking for one that does not exist.
   */
  Optional<ImportFile> findByIdAndOwnerId(UUID id, UUID ownerId);

  /**
   * The file this owner already uploaded with the same content.
   *
   * <p>Used to describe an existing upload in a duplicate response. The unique constraint, not this
   * lookup, is what actually prevents a second copy.
   */
  Optional<ImportFile> findByOwnerIdAndChecksum(UUID ownerId, FileChecksum checksum);
}
