package com.vandunxg.file_processing.fileimport.application.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.vandunxg.file_processing.fileimport.adapter.in.csv.CsvFormatException;
import com.vandunxg.file_processing.fileimport.adapter.in.csv.CsvRecordReader;
import com.vandunxg.file_processing.fileimport.adapter.out.persistence.CustomerUpsertRepository;
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
  private final CustomerImportProcessor customerImportProcessor;
  private final CustomerUpsertRepository customerUpsertRepository;

  @Override
  public UploadFileResult upload(UploadFileCommand command) {
    String storageKey = "imports/" + UUID.randomUUID();
    var storedObject =
        objectStoragePort.store(
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
      saved = fileImportRepositoryPort.save(fileImport);
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
      var completed = fileImportRepositoryPort.save(saved);
      log.info("[upload] fileId={} ownerId={}", completed.getId(), completed.getOwnerId());
      return UploadFileResult.from(completed);
    } catch (RuntimeException exception) {
      if (processed != null && processed.reportKey() != null) {
        cleanUp(processed.reportKey());
      }
      saved.fail();
      try {
        fileImportRepositoryPort.save(saved);
      } catch (RuntimeException persistFailure) {
        exception.addSuppressed(persistFailure);
      }
      throw exception;
    }
  }

  private void validateStoredCsv(String storageKey) {
    try (var input = objectStoragePort.open(storageKey);
        var reader = new CsvRecordReader(input)) {
      reader.next();
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
          var input = objectStoragePort.open(fileImport.getStorageKey())) {
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
          objectStoragePort.store(reportKey, "text/csv", Files.size(report), input);
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
      objectStoragePort.delete(storageKey);
    } catch (RuntimeException cleanupException) {
      log.warn("[upload] failed to clean up uploaded object", cleanupException);
    }
  }
}
