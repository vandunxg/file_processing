package com.vandunxg.file_processing.fileimport.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import com.vandunxg.file_processing.fileimport.application.capability.CustomerImportStaging;
import com.vandunxg.file_processing.fileimport.domain.ImportFileRepository;
import com.vandunxg.file_processing.fileimport.domain.ProcessingJobRepository;
import com.vandunxg.file_processing.fileimport.domain.model.AttemptStatus;
import com.vandunxg.file_processing.fileimport.domain.model.AttemptTrigger;
import com.vandunxg.file_processing.fileimport.domain.model.FileChecksum;
import com.vandunxg.file_processing.fileimport.domain.model.ImportFile;
import com.vandunxg.file_processing.fileimport.domain.model.JobStatus;
import com.vandunxg.file_processing.fileimport.domain.model.ProcessingAttempt;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Exercises the whole worker path against a real database, with object storage replaced by an
 * in-memory stand-in so the file content is under the test's control.
 *
 * <p>The batch size is lowered to two so a handful of rows still produces several batches and
 * several safe points -- the behaviour that matters here only appears between batches.
 */
@PostgresIntegrationTest
@Import(InMemoryStorageConfiguration.class)
@TestPropertySource(
    properties = {"app.file-import.batch-size=2", "app.file-import.progress-row-interval=2"})
class ProcessingJobRunnerIT extends AuthIntegrationTestBase {

  private static final String HEADER = "external_id,full_name,email,phone,date_of_birth,address\n";

  @Autowired private ProcessingJobRunner runner;
  @Autowired private ProcessingJobCommandService commandService;
  @Autowired private ProcessingJobRepository jobs;
  @Autowired private ImportFileRepository files;
  @Autowired private InMemoryFileStorage storage;
  @Autowired private CustomerImportStaging staging;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private TransactionTemplate transactionTemplate;

  @BeforeEach
  void reset() {
    transactionTemplate.executeWithoutResult(
        status -> {
          jdbcTemplate.update("DELETE FROM customer_import_staging_issue");
          jdbcTemplate.update("DELETE FROM customer_import_staging");
          jdbcTemplate.update("DELETE FROM customers");
          jdbcTemplate.update("DELETE FROM processing_attempt");
          jdbcTemplate.update("DELETE FROM processing_job");
          jdbcTemplate.update("DELETE FROM file_import");
        });
    storage.clear();
  }

  @Test
  void aFileOfValidRowsCompletesAndPersistsEveryCustomer() {
    UUID jobId = queue(HEADER + validRow("CUS_01") + validRow("CUS_02") + validRow("CUS_03"));

    assertThat(runner.runNextJob()).isTrue();

    ProcessingJob job = jobs.findById(jobId).orElseThrow();
    assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
    assertThat(job.getProcessedRows()).isEqualTo(3);
    assertThat(job.getValidRows()).isEqualTo(3);
    assertThat(job.getInvalidRows()).isZero();
    assertThat(job.getInsertedRows()).isEqualTo(3);
    assertThat(job.getTotalRows()).isEqualTo(3);
    assertThat(job.getProgressPercent()).isEqualTo(100);
    assertThat(customerCount()).isEqualTo(3);
    assertThat(
            jdbcTemplate.queryForObject("SELECT count(*) FROM customer_import_staging", Long.class))
        .isZero();
  }

  @Test
  void aCompletedJobPublishesNoReportBecauseThereIsNothingToReport() {
    UUID jobId = queue(HEADER + validRow("CUS_01"));

    runner.runNextJob();

    assertThat(jobs.findById(jobId).orElseThrow().getErrorReportKey()).isNull();
    assertThat(storage.keys()).noneMatch(key -> key.startsWith("reports/"));
  }

  @Test
  void rejectedRowsAreReportedWithoutFailingTheJob() {
    UUID jobId =
        queue(
            HEADER
                + validRow("CUS_01")
                + "CUS_02,A,not-an-email,123,2999-01-01,\n"
                + validRow("CUS_03"));

    runner.runNextJob();

    ProcessingJob job = jobs.findById(jobId).orElseThrow();
    assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED_WITH_ERRORS);
    assertThat(job.getProcessedRows()).isEqualTo(3);
    assertThat(job.getValidRows()).isEqualTo(2);
    // One source row was rejected, even though it broke several rules at once.
    assertThat(job.getInvalidRows()).isOne();
    assertThat(job.getErrorReportKey()).isEqualTo("reports/" + jobId + ".csv");
    assertThat(customerCount()).isEqualTo(2);
    assertThat(storage.read(job.getErrorReportKey()))
        .contains("row_number,external_id,error_code,field,error_message,original_data")
        .contains("INVALID_EMAIL");
  }

  @Test
  void firstFieldValidOccurrenceWinsAndReportStaysInPhysicalRowOrder() {
    UUID jobId =
        queue(
            HEADER
                + "CUS_01,Nguyen Van A,invalid,0912345678,2000-01-02,\n"
                + validRow("CUS_01")
                + validRow("CUS_01"));

    runner.runNextJob();

    ProcessingJob job = jobs.findById(jobId).orElseThrow();
    assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED_WITH_ERRORS);
    assertThat(job.getValidRows()).isOne();
    assertThat(job.getInvalidRows()).isEqualTo(2);
    String report = storage.read(job.getErrorReportKey());
    assertThat(report)
        .contains("INVALID_EMAIL")
        .contains("DUPLICATE_EXTERNAL_ID_IN_FILE")
        .satisfies(
            content ->
                assertThat(content.indexOf("INVALID_EMAIL"))
                    .isLessThan(content.indexOf("DUPLICATE_EXTERNAL_ID_IN_FILE")));
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT email FROM customers WHERE external_id = 'CUS_01'", String.class))
        .isEqualTo("cus_01@example.com");
  }

  @Test
  void aStorageFailureFailsTheJobWithASanitizedReasonAndNoReport() {
    UUID jobId = queue(HEADER + validRow("CUS_01"));
    storage.failReads();

    runner.runNextJob();

    ProcessingJob job = jobs.findById(jobId).orElseThrow();
    assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
    assertThat(job.getErrorCode()).isEqualTo("PROCESSING_FAILED");
    assertThat(job.getErrorSummary()).doesNotContain("secret").doesNotContain("bucket-internal");
    assertThat(job.getErrorReportKey()).isNull();
    assertThat(job.getAttempts())
        .singleElement()
        .extracting(ProcessingAttempt::getStatus)
        .isEqualTo(AttemptStatus.FAILED);
  }

  @Test
  void cancellationStopsAtAStagingSafePointBeforeAnyCustomerMerge() {
    UUID jobId =
        queue(
            HEADER
                + validRow("CUS_01")
                + validRow("CUS_02")
                + validRow("CUS_03")
                + validRow("CUS_04")
                + validRow("CUS_05")
                + validRow("CUS_06"));
    // Fires as the worker opens the file, so the job is already PROCESSING and the request lands
    // before the first checkpoint rather than racing the claim.
    storage.onRead(() -> commandService.requestCancellation(jobId, ownerOf(jobId), true));

    runner.runNextJob();

    ProcessingJob job = jobs.findById(jobId).orElseThrow();
    assertThat(job.getStatus()).isEqualTo(JobStatus.CANCELLED);
    // A cancelled run publishes nothing: a partial report must never look like the final one.
    assertThat(job.getErrorReportKey()).isNull();
    assertThat(job.getAttempts().getLast().getStatus()).isEqualTo(AttemptStatus.CANCELLED);
    assertThat(storage.keys()).noneMatch(key -> key.startsWith("reports/"));
    // The new two-phase design only merges canonical rows after parsing reaches EOF. Cancelling
    // while staging therefore leaves customer data untouched; a retry restarts from the original.
    assertThat(job.getProcessedRows()).isLessThan(6);
    assertThat(customerCount()).isZero();
    assertThat(job.getInsertedRows()).isZero();
  }

  @Test
  void retryReplaysTheFileFromTheStartWithCountersBackAtZero() {
    UUID jobId = queue(HEADER + validRow("CUS_01") + validRow("CUS_02"));
    storage.failReads();
    runner.runNextJob();
    assertThat(jobs.findById(jobId).orElseThrow().getStatus()).isEqualTo(JobStatus.FAILED);

    storage.succeedReads();
    commandService.requestRetry(jobId, ownerOf(jobId), true);

    ProcessingJob requeued = jobs.findById(jobId).orElseThrow();
    assertThat(requeued.getStatus()).isEqualTo(JobStatus.QUEUED);
    assertThat(requeued.getProcessedRows()).isZero();
    assertThat(requeued.getAttempts()).hasSize(1);

    assertThat(runner.runNextJob()).isTrue();

    ProcessingJob replayed = jobs.findById(jobId).orElseThrow();
    assertThat(replayed.getStatus()).isEqualTo(JobStatus.COMPLETED);
    assertThat(replayed.getProcessedRows()).isEqualTo(2);
    assertThat(replayed.getInsertedRows()).isEqualTo(2);
    // History is append-only: the failed attempt survives alongside the successful replay.
    assertThat(replayed.getAttempts())
        .extracting(ProcessingAttempt::getAttemptNumber, ProcessingAttempt::getTrigger)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(1, AttemptTrigger.INITIAL),
            org.assertj.core.groups.Tuple.tuple(2, AttemptTrigger.ADMIN_RETRY));
    assertThat(customerCount()).isEqualTo(2);
  }

  @Test
  void replayingAFileDoesNotDuplicateCustomersItAlreadyImported() {
    UUID first = queue(HEADER + validRow("CUS_01") + validRow("CUS_02"));
    runner.runNextJob();

    UUID second = queue(HEADER + validRow("CUS_01") + validRow("CUS_02"));
    runner.runNextJob();

    ProcessingJob job = jobs.findById(second).orElseThrow();
    assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
    assertThat(job.getInsertedRows()).isZero();
    assertThat(job.getUpdatedRows()).isEqualTo(2);
    assertThat(customerCount()).isEqualTo(2);
    assertThat(first).isNotEqualTo(second);
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM customers WHERE last_import_job_id = ?", Long.class, second))
        .isEqualTo(2L);
  }

  @Test
  void runNextJobReportsThatThereWasNothingToDoWhenTheQueueIsEmpty() {
    assertThat(runner.runNextJob()).isFalse();
  }

  @Test
  void aCancellationThatLandsAfterTheReportIsUploadedLeavesNoObjectBehind() {
    // The worker checks for cancellation before uploading the report, but the request can still
    // arrive between that check and the terminal transition. The job then becomes CANCELLED and
    // drops the key, so without this the object stays in the bucket with nothing referencing it --
    // and nothing ever deletes it.
    UUID jobId = queue(HEADER + validRow("CUS_01"));
    commandService.claimNextQueued();
    commandService.requestCancellation(jobId, ownerOf(jobId), true);
    String reportKey = "reports/" + jobId + ".csv";
    storage.put(reportKey, "row_number,external_id\n2,CUS_01\n");

    commandService.complete(jobId, new RowCounters(2, 1, 1, 1, 0), reportKey);

    ProcessingJob job = jobs.findById(jobId).orElseThrow();
    assertThat(job.getStatus()).isEqualTo(JobStatus.CANCELLED);
    assertThat(job.getErrorReportKey()).isNull();
    assertThat(storage.keys()).doesNotContain(reportKey);
  }

  private UUID ownerOf(UUID jobId) {
    return jobs.findById(jobId).orElseThrow().getOwnerId();
  }

  private long customerCount() {
    return jdbcTemplate.queryForObject("SELECT count(*) FROM customers", Long.class);
  }

  private UUID queue(String csv) {
    UUID ownerId = UUID.randomUUID();
    String storageKey = "imports/" + UUID.randomUUID();
    storage.put(storageKey, csv);
    ImportFile file =
        files.save(
            ImportFile.register(
                ownerId,
                "customers.csv",
                storageKey,
                FileChecksum.of(UUID.randomUUID().toString().replace("-", "").repeat(2)),
                csv.length(),
                "text/csv",
                Instant.now().plusSeconds(3600),
                "file-processing",
                StorageProvider.R2));
    return jobs.save(ProcessingJob.queue(file.getId(), ownerId, Instant.now())).getId();
  }

  private static String validRow(String externalId) {
    return externalId
        + ",Nguyen Van A,"
        + externalId.toLowerCase(java.util.Locale.ROOT)
        + "@example.com,0912345678,2000-01-02,1 Main St\n";
  }

  @Test
  void recoveryMarksAJobWhoseWorkerDisappearedAsFailed() {
    UUID jobId = queue(HEADER + validRow("CUS_01"));
    commandService.claimNextQueued();
    goStale(jobId);

    commandService.recoverStaleJob(jobId);

    ProcessingJob job = jobs.findById(jobId).orElseThrow();
    assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
    assertThat(job.getAttempts())
        .singleElement()
        .satisfies(
            attempt -> {
              assertThat(attempt.getStatus()).isEqualTo(AttemptStatus.FAILED);
              assertThat(attempt.getErrorCode()).isEqualTo("WORKER_LOST");
            });
    assertThat(runner.runNextJob()).isFalse();
  }

  @Test
  void recoveryFailsACancellationRequestedJobWhoseWorkerDisappeared() {
    UUID jobId = queue(HEADER + validRow("CUS_01"));
    commandService.claimNextQueued();
    commandService.requestCancellation(jobId, ownerOf(jobId), true);
    goStale(jobId);

    commandService.recoverStaleJob(jobId);

    ProcessingJob job = jobs.findById(jobId).orElseThrow();
    assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
    assertThat(job.getAttempts().getLast().getStatus()).isEqualTo(AttemptStatus.FAILED);
    assertThat(job.getErrorCode()).isEqualTo("WORKER_LOST");
    assertThat(runner.runNextJob()).isFalse();
  }

  @Test
  void aSoftDeletedJobIsInvisibleToEveryBusinessRead() {
    UUID jobId = queue(HEADER + validRow("CUS_01"));
    transactionTemplate.executeWithoutResult(
        status ->
            jdbcTemplate.update(
                "UPDATE processing_job SET deleted_at = now() WHERE id = ?", jobId));

    assertThat(jobs.findById(jobId)).isEmpty();
    assertThat(jobs.findByIdAndOwnerId(jobId, UUID.randomUUID())).isEmpty();
    assertThat(runner.runNextJob()).isFalse();
  }

  @Test
  void recoveryLeavesTheLostAttemptsWorkspaceForTheSweepToRemove() {
    UUID jobId = queue(HEADER + validRow("CUS_01"));
    commandService.claimNextQueued();
    stageRejectedRow(jobId, 1, 2);
    goStale(jobId);

    commandService.recoverStaleJob(jobId);

    // A running attempt's workspace is off limits to the sweep, so the transition itself is what
    // releases these rows -- and it is never at the mercy of the delete that removes them.
    assertThat(jobs.findById(jobId).orElseThrow().getStatus()).isEqualTo(JobStatus.FAILED);
    assertThat(staging.clearAbandoned()).isEqualTo(2);
    assertThat(stagingRows()).isZero();
    assertThat(stagingIssues()).isZero();
  }

  @Test
  void theSweepRemovesWorkspaceRowsNoRunningAttemptOwnsAndSparesTheRest() {
    UUID running = queue(HEADER + validRow("CUS_01"));
    commandService.claimNextQueued();
    stageRejectedRow(running, 1, 2);

    UUID finished = queue(HEADER + validRow("CUS_02"));
    runner.runNextJob();
    // Whatever a failed best-effort cleanup could have left behind for a job that is already done.
    stageRejectedRow(finished, 1, 2);

    assertThat(staging.clearAbandoned()).isEqualTo(2);

    assertThat(stagingRows()).isOne();
    assertThat(
            jdbcTemplate.queryForObject("SELECT job_id FROM customer_import_staging", UUID.class))
        .isEqualTo(running);
  }

  @Test
  void aRowWithSeveralIssuesReportsThemAllEvenWhenOnePageCannotHoldThem() {
    // batch-size is two, so this single page has to carry one row's five issues plus a duplicate
    // marker: the page limit bounds source rows, never report records.
    UUID jobId =
        queue(HEADER + ",,,,,\n" + validRow("CUS_01") + validRow("CUS_01") + validRow("CUS_02"));

    runner.runNextJob();

    ProcessingJob job = jobs.findById(jobId).orElseThrow();
    assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED_WITH_ERRORS);
    assertThat(job.getProcessedRows()).isEqualTo(4);
    assertThat(job.getValidRows()).isEqualTo(2);
    assertThat(job.getInvalidRows()).isEqualTo(2);
    assertThat(customerCount()).isEqualTo(2);

    String report = storage.read(job.getErrorReportKey());
    assertThat(report.lines().filter(line -> line.startsWith("2,")).count()).isEqualTo(5);
    assertThat(report)
        .contains("REQUIRED_FIELD")
        .contains("DUPLICATE_EXTERNAL_ID_IN_FILE")
        .satisfies(
            content ->
                assertThat(content.indexOf("REQUIRED_FIELD"))
                    .isLessThan(content.indexOf("DUPLICATE_EXTERNAL_ID_IN_FILE")));
  }

  private long stagingRows() {
    return jdbcTemplate.queryForObject("SELECT count(*) FROM customer_import_staging", Long.class);
  }

  private long stagingIssues() {
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM customer_import_staging_issue", Long.class);
  }

  private void stageRejectedRow(UUID jobId, int attemptNumber, long rowNumber) {
    transactionTemplate.executeWithoutResult(
        status -> {
          jdbcTemplate.update(
              """
              INSERT INTO customer_import_staging (
                job_id, attempt_number, row_number, validation_passed, original_external_id,
                original_full_name, original_email)
              VALUES (?, ?, ?, false, 'CUS_STAGED', 'Nguyen Van A', 'not-an-email')
              """,
              jobId,
              attemptNumber,
              rowNumber);
          jdbcTemplate.update(
              """
              INSERT INTO customer_import_staging_issue (
                job_id, attempt_number, row_number, issue_order, external_id, error_code,
                field_name, error_message)
              VALUES (?, ?, ?, 0, 'CUS_STAGED', 'INVALID_EMAIL', 'email', 'Email is invalid')
              """,
              jobId,
              attemptNumber,
              rowNumber);
        });
  }

  private void goStale(UUID jobId) {
    transactionTemplate.executeWithoutResult(
        status ->
            jdbcTemplate.update(
                "UPDATE processing_job SET heartbeat_at = now() - interval '1 hour' WHERE id = ?",
                jobId));
  }
}
