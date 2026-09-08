package com.vandunxg.file_processing.fileimport.application.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.vandunxg.file_processing.fileimport.adapter.out.persistence.CustomerUpsertRepository;
import com.vandunxg.file_processing.fileimport.application.FileImportProperties;
import com.vandunxg.file_processing.fileimport.application.capability.CustomerCsvReader;
import com.vandunxg.file_processing.fileimport.application.capability.FileStorage;
import com.vandunxg.file_processing.fileimport.application.command.UploadFileCommand;
import com.vandunxg.file_processing.fileimport.application.exception.CsvFormatException;
import com.vandunxg.file_processing.fileimport.application.exception.FileImportErrorCode;
import com.vandunxg.file_processing.fileimport.application.exception.FileImportException;
import com.vandunxg.file_processing.fileimport.application.result.UploadFileResult;
import com.vandunxg.file_processing.fileimport.domain.ImportFileRepository;
import com.vandunxg.file_processing.fileimport.domain.model.FileImport;
import com.vandunxg.file_processing.fileimport.domain.model.StorageProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j(topic = "UPLOAD-FILE-SERVICE")
public class UploadFileService {

  private final FileStorage fileStorage;
  private final ImportFileRepository importFileRepository;
  private final FileImportProperties properties;
  private final Clock clock;
  private final CustomerCsvReader customerCsvReader;
  private final CustomerImportProcessor customerImportProcessor;
  private final CustomerUpsertRepository customerUpsertRepository;

  public UploadFileResult upload(UploadFileCommand command) {
    String storageKey = "imports/" + UUID.randomUUID();
    var storedObject =
        fileStorage.store(
            storageKey, command.contentType(), command.contentLength(), command.content());
    FileImport saved;
    try {
      validateStoredCsv(storageKey);
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
      saved = importFileRepository.save(fileImport);
    } catch (DataIntegrityViolationException exception) {
      cleanUp(storageKey);
      throw new FileImportException(FileImportErrorCode.DUPLICATE_FILE, exception);
    } catch (RuntimeException exception) {
      cleanUp(storageKey);
      throw exception;
    }
    ProcessedImport processed = null;
    try {
      processed = process(saved);
      saved.complete(
          processed.result().validRows(),
          processed.result().invalidRows(),
          processed.result().insertedRows(),
          processed.result().updatedRows(),
          processed.reportKey());
      var completed = importFileRepository.save(saved);
      log.info("[upload] fileId={} ownerId={}", completed.getId(), completed.getOwnerId());
      return UploadFileResult.from(completed);
    } catch (RuntimeException exception) {
      if (processed != null && processed.reportKey() != null) {
        cleanUp(processed.reportKey());
      }
      saved.fail();
      try {
        importFileRepository.save(saved);
      } catch (RuntimeException persistFailure) {
        exception.addSuppressed(persistFailure);
      }
      throw exception;
    }
  }

  private void validateStoredCsv(String storageKey) {
    try (var input = fileStorage.open(storageKey)) {
      customerCsvReader.validateHeader(input);
    } catch (CsvFormatException exception) {
      throw new FileImportException(FileImportErrorCode.INVALID_CSV_HEADER, exception);
    } catch (IOException exception) {
      throw new FileImportException(FileImportErrorCode.STORAGE_UNAVAILABLE, exception);
    }
  }

  private ProcessedImport process(FileImport fileImport) {
    Path report = null;
    try {
      report = Files.createTempFile("customer-import-", ".csv");
      CustomerImportResult result;
      try (var reportWriter = new CsvErrorReportWriter(report);
          var input = fileStorage.open(fileImport.getStorageKey())) {
        result =
            customerImportProcessor.process(
                input,
                rows -> customerUpsertRepository.upsert(rows, fileImport.getId()),
                invalid ->
                    invalid
                        .issues()
                        .forEach(issue -> reportWriter.write(issue, invalid.originalRow())));
      }
      String reportKey = null;
      if (result.invalidRows() > 0) {
        reportKey = "reports/" + fileImport.getId() + ".csv";
        try (var input = Files.newInputStream(report)) {
          fileStorage.store(reportKey, "text/csv", Files.size(report), input);
        }
      }
      return new ProcessedImport(result, reportKey);
    } catch (IOException exception) {
      throw new FileImportException(FileImportErrorCode.STORAGE_UNAVAILABLE, exception);
    } finally {
      if (report != null) {
        try {
          Files.deleteIfExists(report);
        } catch (IOException ignored) {
          // The report object has already been finalized or will be retried manually in the MVP.
        }
      }
    }
  }

  private record ProcessedImport(CustomerImportResult result, String reportKey) {}

  private void cleanUp(String storageKey) {
    try {
      fileStorage.delete(storageKey);
    } catch (RuntimeException cleanupException) {
      log.warn("[upload] failed to clean up uploaded object", cleanupException);
    }
  }
}
