package com.vandunxg.file_processing.fileimport.application.service;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.vandunxg.file_processing.fileimport.application.FileImportProperties;
import com.vandunxg.file_processing.fileimport.application.capability.CustomerCsvReader;
import com.vandunxg.file_processing.fileimport.application.capability.FileStorage;
import com.vandunxg.file_processing.fileimport.application.command.UploadFileCommand;
import com.vandunxg.file_processing.fileimport.application.exception.CsvFormatException;
import com.vandunxg.file_processing.fileimport.application.exception.FileImportErrorCode;
import com.vandunxg.file_processing.fileimport.application.exception.FileImportException;
import com.vandunxg.file_processing.fileimport.application.result.UploadFileResult;
import com.vandunxg.file_processing.fileimport.domain.ImportFileRepository;
import com.vandunxg.file_processing.fileimport.domain.ProcessingJobRepository;
import com.vandunxg.file_processing.fileimport.domain.model.FileChecksum;
import com.vandunxg.file_processing.fileimport.domain.model.ImportFile;
import com.vandunxg.file_processing.fileimport.domain.model.ProcessingJob;
import com.vandunxg.file_processing.fileimport.domain.model.StorageProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Accepts an upload and queues it for processing.
 *
 * <p>The request stores and validates the file, then commits one {@link ImportFile} and one queued
 * {@link ProcessingJob}. It never reads a data row: a 500 MB file cannot be processed inside an
 * HTTP request, so the caller gets a job to watch instead of a result to wait for.
 *
 * <p>Object storage is slow and external, so it stays outside the database transaction. That leaves
 * a window where a stored object has no metadata row yet, which is why every failure path deletes
 * the object it created.
 */
@Service
@RequiredArgsConstructor
@Slf4j(topic = "FILE-IMPORT-COMMAND")
public class FileImportCommandService {

  private final FileStorage fileStorage;
  private final CustomerCsvReader customerCsvReader;
  private final ImportFileRepository importFileRepository;
  private final ProcessingJobRepository processingJobRepository;
  private final FileImportProperties properties;
  private final TransactionTemplate transactionTemplate;
  private final Clock clock;

  public UploadFileResult upload(UploadFileCommand command) {
    if (command.contentLength() > properties.maxFileSize().toBytes()) {
      throw new FileImportException(FileImportErrorCode.FILE_IMPORT_FILE_TOO_LARGE);
    }
    String storageKey = "imports/" + UUID.randomUUID();
    FileStorage.StoredObject stored =
        fileStorage.store(
            storageKey, command.contentType(), command.contentLength(), command.content());
    try {
      validateStoredCsv(storageKey);
      return register(command, storageKey, stored);
    } catch (DataIntegrityViolationException exception) {
      // The unique constraint, not the earlier lookup, is what decides a duplicate: two concurrent
      // uploads of the same bytes can both pass a pre-check and only one may win here.
      cleanUp(storageKey);
      log.info("[upload] duplicate rejected ownerId={}", command.ownerId());
      throw new FileImportException(FileImportErrorCode.FILE_IMPORT_DUPLICATE_FILE, exception);
    } catch (RuntimeException exception) {
      cleanUp(storageKey);
      throw exception;
    }
  }

  private UploadFileResult register(
      UploadFileCommand command, String storageKey, FileStorage.StoredObject stored) {
    Instant now = Instant.now(clock);
    ImportFile file =
        ImportFile.register(
            command.ownerId(),
            command.originalFilename(),
            storageKey,
            FileChecksum.of(stored.checksumSha256()),
            stored.sizeBytes(),
            stored.contentType(),
            now.plus(properties.retention()),
            stored.bucket(),
            StorageProvider.R2);

    UploadFileResult result =
        transactionTemplate.execute(
            status -> {
              ImportFile savedFile = importFileRepository.save(file);
              ProcessingJob job =
                  processingJobRepository.save(
                      ProcessingJob.queue(savedFile.getId(), savedFile.getOwnerId(), now));
              return new UploadFileResult(
                  savedFile.getId(),
                  job.getId(),
                  job.getStatus(),
                  savedFile.getOriginalFilename(),
                  savedFile.getSizeBytes(),
                  savedFile.getChecksum().value(),
                  now);
            });

    log.info(
        "[upload] accepted fileId={} jobId={} ownerId={}",
        result.fileId(),
        result.jobId(),
        command.ownerId());
    return result;
  }

  private void validateStoredCsv(String storageKey) {
    try (var input = fileStorage.open(storageKey)) {
      customerCsvReader.validateHeader(input);
    } catch (CsvFormatException exception) {
      throw new FileImportException(FileImportErrorCode.FILE_IMPORT_INVALID_CSV_HEADER, exception);
    } catch (IOException exception) {
      throw new FileImportException(FileImportErrorCode.FILE_IMPORT_STORAGE_UNAVAILABLE, exception);
    }
  }

  private void cleanUp(String storageKey) {
    try {
      fileStorage.delete(storageKey);
    } catch (RuntimeException cleanupException) {
      log.warn("[upload] failed to clean up stored object key={}", storageKey, cleanupException);
    }
  }
}
