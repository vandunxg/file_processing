package com.vandunxg.file_processing.fileimport.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import com.vandunxg.file_processing.fileimport.application.capability.FileStorage;
import com.vandunxg.file_processing.fileimport.domain.ImportFileRepository;
import com.vandunxg.file_processing.fileimport.domain.ProcessingJobRepository;
import com.vandunxg.file_processing.fileimport.domain.model.AttemptStatus;
import com.vandunxg.file_processing.fileimport.domain.model.AttemptTrigger;
import com.vandunxg.file_processing.fileimport.domain.model.FileChecksum;
import com.vandunxg.file_processing.fileimport.domain.model.ImportFile;
import com.vandunxg.file_processing.fileimport.domain.model.JobStatus;
import com.vandunxg.file_processing.fileimport.domain.model.ProcessingAttempt;
import com.vandunxg.file_processing.fileimport.domain.model.ProcessingJob;
import com.vandunxg.file_processing.fileimport.domain.model.StorageProvider;
import com.vandunxg.file_processing.testsupport.AuthIntegrationTestBase;
import com.vandunxg.file_processing.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
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
@Import(ProcessingJobRunnerIT.InMemoryStorageConfiguration.class)
@TestPropertySource(
    properties = {
      "app.file-import.batch-size=2",
      "app.file-import.progress-row-interval=2",
      "app.file-import.poll-interval=1h",
      "app.file-import.stale-heartbeat-threshold=1h"
    })
class ProcessingJobRunnerIT extends AuthIntegrationTestBase {

  private static final String HEADER = "external_id,full_name,email,phone,date_of_birth,address\n";

  @Autowired private ProcessingJobRunner runner;
  @Autowired private ProcessingJobCommandService commandService;
  @Autowired private ProcessingJobRepository jobs;
  @Autowired private ImportFileRepository files;
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
  void aRowRepeatedInTheSameFileIsProcessedOnceAndRejectedAfterwards() {
    UUID jobId = queue(HEADER + validRow("CUS_01") + validRow("CUS_01"));

    runner.runNextJob();

    ProcessingJob job = jobs.findById(jobId).orElseThrow();
    assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED_WITH_ERRORS);
    assertThat(job.getValidRows()).isOne();
    assertThat(job.getInvalidRows()).isOne();
    assertThat(storage.read(job.getErrorReportKey())).contains("DUPLICATE_EXTERNAL_ID_IN_FILE");
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
  void cancellationStopsAtASafePointAndKeepsTheBatchesAlreadyCommitted() {
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
    // It stopped early, and everything committed before the safe point survived.
    assertThat(job.getProcessedRows()).isLessThan(6);
    assertThat(customerCount()).isEqualTo(job.getInsertedRows());
    assertThat(customerCount()).isPositive();
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

  @TestConfiguration
  static class InMemoryStorageConfiguration {

    @Bean
    @Primary
    InMemoryFileStorage inMemoryFileStorage() {
      return new InMemoryFileStorage();
    }
  }

  /** Stands in for object storage so the test owns the bytes and can make reads fail on demand. */
  static class InMemoryFileStorage implements FileStorage {

    private final Map<String, byte[]> objects = new HashMap<>();
    private boolean readsFail;
    private Runnable onRead = () -> {};

    void put(String key, String content) {
      objects.put(key, content.getBytes(StandardCharsets.UTF_8));
    }

    String read(String key) {
      return new String(objects.get(key), StandardCharsets.UTF_8);
    }

    java.util.Set<String> keys() {
      return objects.keySet();
    }

    void clear() {
      objects.clear();
      readsFail = false;
      onRead = () -> {};
    }

    void failReads() {
      readsFail = true;
    }

    void succeedReads() {
      readsFail = false;
    }

    /** Runs once when the worker opens the original, letting a test interleave with the run. */
    void onRead(Runnable hook) {
      this.onRead = hook;
    }

    @Override
    public StoredObject store(
        String storageKey, String contentType, long contentLength, InputStream content) {
      try (content) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        content.transferTo(buffer);
        byte[] stored = buffer.toByteArray();
        objects.put(storageKey, stored);
        return new StoredObject("file-processing", stored.length, sha256(stored), contentType);
      } catch (IOException exception) {
        throw new UncheckedIOException(exception);
      }
    }

    @Override
    public InputStream open(String storageKey) {
      if (readsFail) {
        throw new IllegalStateException("storage unavailable: secret bucket-internal detail");
      }
      onRead.run();
      byte[] content = objects.get(storageKey);
      if (content == null) {
        throw new IllegalStateException("object not found");
      }
      return new ByteArrayInputStream(content);
    }

    @Override
    public void delete(String storageKey) {
      objects.remove(storageKey);
    }

    /** A real digest, so identical bytes really do collide the way the duplicate rule expects. */
    private static String sha256(byte[] content) {
      try {
        return HexFormat.of()
            .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
      } catch (java.security.NoSuchAlgorithmException exception) {
        throw new IllegalStateException(exception);
      }
    }
  }
}
