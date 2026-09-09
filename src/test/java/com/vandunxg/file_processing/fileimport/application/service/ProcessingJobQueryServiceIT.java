package com.vandunxg.file_processing.fileimport.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import com.vandunxg.file_processing.fileimport.application.exception.FileImportErrorCode;
import com.vandunxg.file_processing.fileimport.application.exception.FileImportException;
import com.vandunxg.file_processing.fileimport.application.result.ProcessingJobAction;
import com.vandunxg.file_processing.fileimport.domain.ImportFileRepository;
import com.vandunxg.file_processing.fileimport.domain.ProcessingJobRepository;
import com.vandunxg.file_processing.fileimport.domain.model.FileChecksum;
import com.vandunxg.file_processing.fileimport.domain.model.ImportFile;
import com.vandunxg.file_processing.fileimport.domain.model.ProcessingJob;
import com.vandunxg.file_processing.fileimport.domain.model.RowCounters;
import com.vandunxg.file_processing.fileimport.domain.model.StorageProvider;
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

/** What a caller gets when the report they are entitled to can no longer be served. */
@PostgresIntegrationTest
@Import(InMemoryStorageConfiguration.class)
class ProcessingJobQueryServiceIT extends AuthIntegrationTestBase {

  private static final String REPORT_KEY = "reports/errors.csv";

  @Autowired private ProcessingJobQueryService queryService;
  @Autowired private ProcessingJobRepository jobs;
  @Autowired private ImportFileRepository files;
  @Autowired private InMemoryFileStorage storage;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private TransactionTemplate transactionTemplate;

  @BeforeEach
  void reset() {
    transactionTemplate.executeWithoutResult(
        status -> {
          // Customers first: their provenance column references the job rows below.
          jdbcTemplate.update("DELETE FROM customers");
          jdbcTemplate.update("DELETE FROM processing_attempt");
          jdbcTemplate.update("DELETE FROM processing_job");
          jdbcTemplate.update("DELETE FROM file_import");
        });
    storage.clear();
  }

  @Test
  void aReportIsServedWhileTheOriginalIsStillWithinRetention() {
    UUID ownerId = UUID.randomUUID();
    ProcessingJob job = completedWithErrors(ownerId, Instant.now().plusSeconds(3600));
    storage.put(REPORT_KEY, "row_number,field,code\n2,email,INVALID_EMAIL\n");

    assertThat(queryService.openErrorReport(job.getId(), ownerId, false)).isNotNull();
    assertThat(queryService.get(job.getId(), ownerId, false).availableActions())
        .contains(ProcessingJobAction.DOWNLOAD_ERROR_REPORT);
  }

  @Test
  void aReportWhoseRetentionHasPassedIsGoneRatherThanAServerError() {
    UUID ownerId = UUID.randomUUID();
    ProcessingJob job = completedWithErrors(ownerId, Instant.now().minusSeconds(1));

    // Retention is the contract for how long the report exists, so asking for it afterwards is a
    // gone resource. Reaching storage instead would surface the SDK's own failure as a 500.
    assertThatThrownBy(() -> queryService.openErrorReport(job.getId(), ownerId, false))
        .isInstanceOf(FileImportException.class)
        .extracting(exception -> ((FileImportException) exception).getError())
        .isEqualTo(FileImportErrorCode.PROCESSING_JOB_REPORT_EXPIRED);
  }

  @Test
  void anExpiredReportIsNotOfferedAsAnAvailableAction() {
    UUID ownerId = UUID.randomUUID();
    ProcessingJob job = completedWithErrors(ownerId, Instant.now().minusSeconds(1));

    assertThat(queryService.get(job.getId(), ownerId, false).availableActions())
        .doesNotContain(ProcessingJobAction.DOWNLOAD_ERROR_REPORT);
  }

  @Test
  void aStorageFailureBecomesAnUnavailableAnswerAndNotTheStorageErrorItself() {
    UUID ownerId = UUID.randomUUID();
    ProcessingJob job = completedWithErrors(ownerId, Instant.now().plusSeconds(3600));
    storage.put(REPORT_KEY, "row_number,field,code\n");
    storage.failReads();

    assertThatThrownBy(() -> queryService.openErrorReport(job.getId(), ownerId, false))
        .isInstanceOf(FileImportException.class)
        .extracting(exception -> ((FileImportException) exception).getError())
        .isEqualTo(FileImportErrorCode.FILE_IMPORT_STORAGE_UNAVAILABLE);
  }

  private ProcessingJob completedWithErrors(UUID ownerId, Instant retentionDeadline) {
    ImportFile file =
        files.save(
            ImportFile.register(
                ownerId,
                "customers.csv",
                "imports/" + UUID.randomUUID(),
                FileChecksum.of(UUID.randomUUID().toString().replace("-", "").repeat(2)),
                64,
                "text/csv",
                retentionDeadline,
                "file-processing",
                StorageProvider.R2));
    ProcessingJob job = jobs.save(ProcessingJob.queue(file.getId(), ownerId, Instant.now()));
    ProcessingJob claimed = jobs.claimNextQueued(Instant.now()).orElseThrow();
    claimed.recordProgress(new RowCounters(2, 1, 1, 1, 0), Instant.now());
    claimed.complete(REPORT_KEY, Instant.now());
    jobs.save(claimed);
    return job;
  }
}
