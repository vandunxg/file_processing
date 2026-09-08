package com.vandunxg.file_processing.fileimport.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import com.vandunxg.file_processing.fileimport.application.command.UploadFileCommand;
import com.vandunxg.file_processing.fileimport.application.exception.FileImportErrorCode;
import com.vandunxg.file_processing.fileimport.application.exception.FileImportException;
import com.vandunxg.file_processing.fileimport.application.result.UploadFileResult;
import com.vandunxg.file_processing.fileimport.domain.ProcessingJobRepository;
import com.vandunxg.file_processing.fileimport.domain.model.JobStatus;
import com.vandunxg.file_processing.testsupport.AuthIntegrationTestBase;
import com.vandunxg.file_processing.testsupport.InMemoryFileStorage;
import com.vandunxg.file_processing.testsupport.InMemoryStorageConfiguration;
import com.vandunxg.file_processing.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Covers what an upload is allowed to do during the request, and what it must leave to the worker.
 */
@PostgresIntegrationTest
@Import(InMemoryStorageConfiguration.class)
class FileImportCommandServiceIT extends AuthIntegrationTestBase {

  private static final String CSV =
      "external_id,full_name,email,phone,date_of_birth,address\n"
          + "CUS_01,Nguyen Van A,a@example.com,0912345678,2000-01-02,1 Main St\n";

  @Autowired private FileImportCommandService service;
  @Autowired private ProcessingJobRepository jobs;
  @Autowired private InMemoryFileStorage storage;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private TransactionTemplate transactionTemplate;

  @BeforeEach
  void reset() {
    transactionTemplate.executeWithoutResult(
        status -> {
          jdbcTemplate.update("DELETE FROM customers");
          jdbcTemplate.update("DELETE FROM processing_attempt");
          jdbcTemplate.update("DELETE FROM processing_job");
          jdbcTemplate.update("DELETE FROM file_import");
        });
    storage.clear();
  }

  @Test
  void uploadRegistersAQueuedJobWithoutImportingASingleRow() {
    UUID ownerId = UUID.randomUUID();

    UploadFileResult result = service.upload(command(ownerId));

    assertThat(result.jobStatus()).isEqualTo(JobStatus.QUEUED);
    assertThat(result.fileId()).isNotNull();
    assertThat(result.jobId()).isNotNull();
    assertThat(result.originalFilename()).isEqualTo("customers.csv");
    assertThat(jobs.findById(result.jobId()).orElseThrow().getStatus()).isEqualTo(JobStatus.QUEUED);
    // The request must not do the work: a customer written here would mean rows were processed
    // inside the HTTP call, which cannot hold for a 500 MB file.
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM customers", Long.class)).isZero();
    assertThat(jobs.findById(result.jobId()).orElseThrow().getAttempts()).isEmpty();
  }

  @Test
  void theSameOwnerUploadingTheSameBytesTwiceGetsAConflictAndNoSecondJob() {
    UUID ownerId = UUID.randomUUID();
    service.upload(command(ownerId));

    assertThatThrownBy(() -> service.upload(command(ownerId)))
        .isInstanceOf(FileImportException.class)
        .extracting(exception -> ((FileImportException) exception).getError())
        .isEqualTo(FileImportErrorCode.FILE_IMPORT_DUPLICATE_FILE);

    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM file_import", Long.class)).isOne();
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM processing_job", Long.class))
        .isOne();
  }

  @Test
  void aRejectedDuplicateLeavesNoOrphanObjectBehindInStorage() {
    UUID ownerId = UUID.randomUUID();
    service.upload(command(ownerId));
    int afterFirstUpload = storage.keys().size();

    assertThatThrownBy(() -> service.upload(command(ownerId)))
        .isInstanceOf(FileImportException.class);

    assertThat(storage.keys()).hasSize(afterFirstUpload);
  }

  @Test
  void differentOwnersMayUploadIdenticalBytesAndEachGetsTheirOwnJob() {
    UploadFileResult first = service.upload(command(UUID.randomUUID()));
    UploadFileResult second = service.upload(command(UUID.randomUUID()));

    assertThat(first.fileId()).isNotEqualTo(second.fileId());
    assertThat(first.jobId()).isNotEqualTo(second.jobId());
    assertThat(first.checksumSha256()).isNotNull();
  }

  @Test
  void aFileThatIsNotUsableCsvIsRejectedAndItsStoredObjectIsRemoved() {
    assertThatThrownBy(
            () ->
                service.upload(
                    new UploadFileCommand(
                        UUID.randomUUID(),
                        "customers.csv",
                        "application/octet-stream",
                        10,
                        new ByteArrayInputStream(
                            "not a csv header\n".getBytes(StandardCharsets.UTF_8)))))
        .isInstanceOf(FileImportException.class)
        .extracting(exception -> ((FileImportException) exception).getError())
        .isEqualTo(FileImportErrorCode.FILE_IMPORT_INVALID_CSV_HEADER);

    assertThat(storage.keys()).isEmpty();
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM file_import", Long.class))
        .isZero();
  }

  @Test
  void anUploadLargerThanTheConfiguredLimitIsRejectedBeforeAnythingIsStored() {
    assertThatThrownBy(
            () ->
                service.upload(
                    new UploadFileCommand(
                        UUID.randomUUID(),
                        "customers.csv",
                        "application/octet-stream",
                        600L * 1024 * 1024,
                        new ByteArrayInputStream(CSV.getBytes(StandardCharsets.UTF_8)))))
        .isInstanceOf(FileImportException.class)
        .extracting(exception -> ((FileImportException) exception).getError())
        .isEqualTo(FileImportErrorCode.FILE_IMPORT_FILE_TOO_LARGE);

    assertThat(storage.keys()).isEmpty();
  }

  private static UploadFileCommand command(UUID ownerId) {
    return new UploadFileCommand(
        ownerId,
        "customers.csv",
        "application/octet-stream",
        CSV.length(),
        new ByteArrayInputStream(CSV.getBytes(StandardCharsets.UTF_8)));
  }
}
