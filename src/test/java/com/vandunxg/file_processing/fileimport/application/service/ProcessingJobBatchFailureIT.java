package com.vandunxg.file_processing.fileimport.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.vandunxg.file_processing.customer.application.capability.CustomerBatchWriter;
import com.vandunxg.file_processing.customer.application.result.ImportCustomerBatchResult;
import com.vandunxg.file_processing.customer.domain.model.Customer;
import com.vandunxg.file_processing.customer.infrastructure.persistence.PostgresCustomerBatchWriter;
import com.vandunxg.file_processing.fileimport.domain.ImportFileRepository;
import com.vandunxg.file_processing.fileimport.domain.ProcessingJobRepository;
import com.vandunxg.file_processing.fileimport.domain.model.AttemptStatus;
import com.vandunxg.file_processing.fileimport.domain.model.AttemptTrigger;
import com.vandunxg.file_processing.fileimport.domain.model.FileChecksum;
import com.vandunxg.file_processing.fileimport.domain.model.ImportFile;
import com.vandunxg.file_processing.fileimport.domain.model.JobStatus;
import com.vandunxg.file_processing.fileimport.domain.model.ProcessingJob;
import com.vandunxg.file_processing.fileimport.domain.model.StorageProvider;
import com.vandunxg.file_processing.testsupport.AuthIntegrationTestBase;
import com.vandunxg.file_processing.testsupport.InMemoryFileStorage;
import com.vandunxg.file_processing.testsupport.InMemoryStorageConfiguration;
import com.vandunxg.file_processing.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What survives when the database refuses a batch part-way through a run.
 *
 * <p>The batch is the transaction, so a failure must lose exactly that batch. Anything wider would
 * mean a 500 MB import is all-or-nothing, which is the whole reason the pipeline is batched; and a
 * replay must not create a second customer for a row an earlier batch already committed.
 */
@PostgresIntegrationTest
@Import({InMemoryStorageConfiguration.class, ProcessingJobBatchFailureIT.FailingWriter.class})
@TestPropertySource(properties = "app.file-import.batch-size=2")
class ProcessingJobBatchFailureIT extends AuthIntegrationTestBase {

  private static final String HEADER = "external_id,full_name,email,phone,date_of_birth,address\n";

  @Autowired private ProcessingJobRunner runner;
  @Autowired private ProcessingJobRepository jobs;
  @Autowired private ImportFileRepository files;
  @Autowired private InMemoryFileStorage storage;
  @Autowired private FailOnNthBatchWriter writer;
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
    writer.reset();
  }

  @Test
  void aBatchThatFailsInTheDatabaseKeepsTheBatchesAlreadyCommitted() {
    UUID jobId = queue(fourRows());
    writer.failOnBatch(2);

    assertThat(runner.runNextJob()).isTrue();

    ProcessingJob job = jobs.findById(jobId).orElseThrow();
    assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
    assertThat(job.getAttempts().getLast().getStatus()).isEqualTo(AttemptStatus.FAILED);
    // Batch one committed on its own transaction and the failure of batch two cannot undo it.
    assertThat(customerCount()).isEqualTo(2);
    // The raw driver message must not reach the caller.
    assertThat(job.getErrorSummary()).doesNotContain("simulated");
  }

  @Test
  void retryAfterADatabaseFailureReplaysEveryRowWithoutDuplicatingTheCommittedOnes() {
    UUID jobId = queue(fourRows());
    writer.failOnBatch(2);
    runner.runNextJob();

    writer.reset();
    ProcessingJob failed = jobs.findById(jobId).orElseThrow();
    failed.requestRetry(AttemptTrigger.USER_RETRY);
    jobs.save(failed);

    assertThat(runner.runNextJob()).isTrue();

    ProcessingJob replayed = jobs.findById(jobId).orElseThrow();
    assertThat(replayed.getStatus()).isEqualTo(JobStatus.COMPLETED);
    assertThat(replayed.getProcessedRows()).isEqualTo(4);
    // Four distinct customers, not six: the rows batch one already wrote are upserted, not
    // inserted a second time.
    assertThat(customerCount()).isEqualTo(4);
  }

  private static String fourRows() {
    StringBuilder csv = new StringBuilder(HEADER);
    for (int row = 1; row <= 4; row++) {
      csv.append("CUS_0")
          .append(row)
          .append(",Nguyen Van A,cus_0")
          .append(row)
          .append("@example.com,0912345678,2000-01-02,1 Main St\n");
    }
    return csv.toString();
  }

  private Long customerCount() {
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

  @TestConfiguration
  static class FailingWriter {

    @Bean
    @Primary
    FailOnNthBatchWriter failOnNthBatchWriter(PostgresCustomerBatchWriter delegate) {
      return new FailOnNthBatchWriter(delegate);
    }
  }

  /**
   * Writes batches for real until the chosen one, which fails the way a lost connection would.
   *
   * <p>Delegating for the earlier batches is the point: the test can only prove committed batches
   * survive if they were genuinely committed.
   */
  static class FailOnNthBatchWriter implements CustomerBatchWriter {

    private final CustomerBatchWriter delegate;
    private final AtomicInteger batches = new AtomicInteger();
    private volatile int failingBatch;

    FailOnNthBatchWriter(CustomerBatchWriter delegate) {
      this.delegate = delegate;
    }

    void failOnBatch(int batch) {
      this.failingBatch = batch;
    }

    void reset() {
      batches.set(0);
      failingBatch = 0;
    }

    @Override
    public ImportCustomerBatchResult upsertAll(List<Customer> customers) {
      if (batches.incrementAndGet() == failingBatch) {
        throw new DataAccessResourceFailureException("simulated connection loss");
      }
      return delegate.upsertAll(customers);
    }
  }
}
