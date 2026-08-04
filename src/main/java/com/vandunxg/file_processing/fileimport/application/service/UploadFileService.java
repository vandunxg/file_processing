package com.vandunxg.file_processing.fileimport.application.service;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.vandunxg.file_processing.fileimport.application.command.UploadFileCommand;
import com.vandunxg.file_processing.fileimport.application.port.in.UploadFileUseCase;
import com.vandunxg.file_processing.fileimport.application.port.out.FileImportRepositoryPort;
import com.vandunxg.file_processing.fileimport.application.port.out.ObjectStoragePort;
import com.vandunxg.file_processing.fileimport.application.result.UploadFileResult;
import com.vandunxg.file_processing.fileimport.configuration.FileImportProperties;
import com.vandunxg.file_processing.fileimport.domain.exception.FileImportErrorCode;
import com.vandunxg.file_processing.fileimport.domain.exception.FileImportException;
import com.vandunxg.file_processing.fileimport.domain.model.FileImport;
import com.vandunxg.file_processing.fileimport.domain.model.StorageProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j(topic = "UPLOAD-FILE-SERVICE")
public class UploadFileService implements UploadFileUseCase {

  private final ObjectStoragePort objectStoragePort;
  private final FileImportRepositoryPort fileImportRepositoryPort;
  private final FileImportProperties properties;
  private final Clock clock;

  @Override
  public UploadFileResult upload(UploadFileCommand command) {
    String storageKey = "imports/" + UUID.randomUUID();
    var storedObject =
        objectStoragePort.store(
            storageKey, command.contentType(), command.contentLength(), command.content());
    try {
      FileImport fileImport =
          FileImport.register(
              command.ownerId(),
              command.originalFilename(),
              storageKey,
              storedObject.checksumSha256(),
              storedObject.sizeBytes(),
              storedObject.contentType(),
              Instant.now(clock).plus(properties.retention()),
              storedObject.bucket(),
              StorageProvider.R2);
      var saved = fileImportRepositoryPort.save(fileImport);
      log.info("[upload] fileId={} ownerId={}", saved.getId(), saved.getOwnerId());
      return UploadFileResult.from(saved);
    } catch (DataIntegrityViolationException exception) {
      cleanUp(storageKey);
      throw new FileImportException(FileImportErrorCode.DUPLICATE_FILE, exception);
    } catch (RuntimeException exception) {
      cleanUp(storageKey);
      throw exception;
    }
  }

  private void cleanUp(String storageKey) {
    try {
      objectStoragePort.delete(storageKey);
    } catch (RuntimeException cleanupException) {
      log.warn("[upload] failed to clean up uploaded object", cleanupException);
    }
  }
}
