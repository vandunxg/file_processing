package com.vandunxg.file_processing.fileimport.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.vandunxg.file_processing.fileimport.domain.model.FileChecksum;
import com.vandunxg.file_processing.fileimport.domain.model.ImportFile;

/** Aggregate repository for the immutable metadata of an accepted import file. */
public interface ImportFileRepository {

  ImportFile save(ImportFile importFile);

  Optional<ImportFile> findById(UUID id);

  Optional<ImportFile> findByOwnerIdAndChecksum(UUID ownerId, FileChecksum checksum);

  /**
   * Loads a file only when the caller owns it, so asking for someone else's file is
   * indistinguishable from asking for one that does not exist.
   */
  Optional<ImportFile> findByIdAndOwnerId(UUID id, UUID ownerId);

  /**
   * Loads the files behind a page of jobs.
   *
   * <p>One lookup for the whole page: a list resolves a filename per row, and doing that one id at
   * a time is a query per row.
   */
  List<ImportFile> findAllByIds(Collection<UUID> ids);

  /**
   * Metadata remains for history after this point; only its stored objects are eligible to purge.
   */
  List<ImportFile> findExpired(Instant retentionBefore);
}
