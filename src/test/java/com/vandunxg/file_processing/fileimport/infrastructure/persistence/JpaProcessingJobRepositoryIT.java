package com.vandunxg.file_processing.fileimport.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.vandunxg.file_processing.fileimport.domain.ProcessingJobRepository;
import com.vandunxg.file_processing.fileimport.domain.model.AttemptStatus;
import com.vandunxg.file_processing.fileimport.domain.model.AttemptTrigger;
import com.vandunxg.file_processing.fileimport.domain.model.JobStatus;
import com.vandunxg.file_processing.fileimport.domain.model.ProcessingAttempt;
import com.vandunxg.file_processing.fileimport.domain.model.ProcessingJob;
import com.vandunxg.file_processing.testsupport.AuthIntegrationTestBase;
import com.vandunxg.file_processing.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

@PostgresIntegrationTest
class JpaProcessingJobRepositoryIT extends AuthIntegrationTestBase {

  private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");

  @Autowired private ProcessingJobRepository repository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private TransactionTemplate transactionTemplate;

  @BeforeEach
  void resetProcessingTables() {
    // The pool runs with auto-commit off, so fixture DML outside a transaction would be rolled
    // back when the connection is returned.
    transactionTemplate.executeWithoutResult(
        status -> {
          jdbcTemplate.update("DELETE FROM processing_attempt");
          jdbcTemplate.update("DELETE FROM processing_job");
          jdbcTemplate.update("DELETE FROM file_import");
        });
  }

  @Test
  void exactlyOneWorkerClaimsAJobWhenManyCompeteForIt() throws Exception {
    repository.save(ProcessingJob.queue(insertImportFile(), UUID.randomUUID(), NOW));

    int workers = 8;
    List<Callable<Optional<ProcessingJob>>> claims =
        java.util.Collections.nCopies(workers, () -> repository.claimNextQueued(NOW));
    List<Future<Optional<ProcessingJob>>> results;
    try (var pool = Executors.newFixedThreadPool(workers)) {
      results = pool.invokeAll(claims);
    }

    long winners = 0;
    for (Future<Optional<ProcessingJob>> result : results) {
      if (result.get().isPresent()) {
        winners++;
      }
    }

    assertThat(winners).isOne();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM processing_attempt WHERE status = 'RUNNING'", Long.class))
        .isOne();
    assertThat(
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM processing_job WHERE status = 'PROCESSING'", Long.class))
        .isOne();
  }

  @Test
  void claimOpensTheNextAttemptWithTheTriggerTheRetryAskedFor() {
    ProcessingJob queued =
        repository.save(ProcessingJob.queue(insertImportFile(), UUID.randomUUID(), NOW));

    ProcessingJob claimed = repository.claimNextQueued(NOW).orElseThrow();
    claimed.fail("DATABASE_BATCH_FAILED", "database batch failed", NOW.plusSeconds(1));
    claimed.requestRetry(AttemptTrigger.USER_RETRY);
    repository.save(claimed);

    ProcessingJob reclaimed = repository.claimNextQueued(NOW.plusSeconds(2)).orElseThrow();

    assertThat(reclaimed.getId()).isEqualTo(queued.getId());
    assertThat(reclaimed.getCurrentAttempt()).isEqualTo(2);
    assertThat(reclaimed.getAttempts())
        .extracting(ProcessingAttempt::getAttemptNumber, ProcessingAttempt::getTrigger)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(1, AttemptTrigger.INITIAL),
            org.assertj.core.groups.Tuple.tuple(2, AttemptTrigger.USER_RETRY));
    assertThat(reclaimed.getAttempts().getFirst().getStatus()).isEqualTo(AttemptStatus.FAILED);
  }

  @Test
  void claimReturnsEmptyWhenNoJobIsQueued() {
    ProcessingJob job =
        repository.save(ProcessingJob.queue(insertImportFile(), UUID.randomUUID(), NOW));
    repository.claimNextQueued(NOW);

    assertThat(repository.claimNextQueued(NOW.plusSeconds(1))).isEmpty();
    assertThat(repository.findById(job.getId()).orElseThrow().getStatus())
        .isEqualTo(JobStatus.PROCESSING);
  }

  @Test
  void findByIdAndOwnerIdHidesAJobOwnedBySomebodyElse() {
    UUID ownerId = UUID.randomUUID();
    ProcessingJob job = repository.save(ProcessingJob.queue(insertImportFile(), ownerId, NOW));

    assertThat(repository.findByIdAndOwnerId(job.getId(), ownerId)).isPresent();
    assertThat(repository.findByIdAndOwnerId(job.getId(), UUID.randomUUID())).isEmpty();
  }

  @Test
  void findStaleReportsOnlyJobsWhoseWorkerStoppedReportingProgress() {
    ProcessingJob fresh =
        repository.save(ProcessingJob.queue(insertImportFile(), UUID.randomUUID(), NOW));
    repository.claimNextQueued(NOW);
    ProcessingJob stale =
        repository.save(ProcessingJob.queue(insertImportFile(), UUID.randomUUID(), NOW));
    repository.claimNextQueued(NOW.minusSeconds(600));

    List<ProcessingJob> found = repository.findStale(NOW.minusSeconds(60));

    assertThat(found).extracting(ProcessingJob::getId).containsExactly(stale.getId());
    assertThat(found).extracting(ProcessingJob::getId).doesNotContain(fresh.getId());
  }

  /**
   * A write merges the aggregate over the stored rows, and the history it carries is append-only.
   * Were the merge to replace the collection rather than update it, the earlier attempts would be
   * deleted and reinserted -- the rows would still look right while their identity and creation
   * time, the only record of when that run actually happened, had silently changed.
   */
  @Test
  void savingAJobUpdatesItsEarlierAttemptsInPlaceInsteadOfReplacingThem() {
    repository.save(ProcessingJob.queue(insertImportFile(), UUID.randomUUID(), NOW));
    ProcessingJob claimed = repository.claimNextQueued(NOW).orElseThrow();
    Map<String, Object> firstAttemptBefore = attemptRows().getFirst();

    claimed.fail("DATABASE_BATCH_FAILED", "database batch failed", NOW.plusSeconds(1));
    claimed.requestRetry(AttemptTrigger.USER_RETRY);
    repository.save(claimed);
    repository.claimNextQueued(NOW.plusSeconds(2)).orElseThrow();

    List<Map<String, Object>> attempts = attemptRows();
    assertThat(attempts).hasSize(2);
    assertThat(attempts.getFirst())
        .containsEntry("id", firstAttemptBefore.get("id"))
        .containsEntry("created_at", firstAttemptBefore.get("created_at"))
        .containsEntry("status", AttemptStatus.FAILED.name());
    assertThat(attempts.get(1)).containsEntry("status", AttemptStatus.RUNNING.name());
  }

  /**
   * The creation audit survives the merge that a write performs.
   *
   * <p>What makes this true is {@code updatable = false} on {@code AuditableEntity}, not anything
   * the mapper does -- so this pins the guarantee where a caller actually depends on it: an entity
   * that dropped that flag, or a write path that stopped going through it, shows up here.
   */
  @Test
  void theCreationAuditOfAJobSurvivesEveryLaterWrite() {
    ProcessingJob queued =
        repository.save(ProcessingJob.queue(insertImportFile(), UUID.randomUUID(), NOW));
    Object createdAtAfterInsert = jobRow(queued.getId()).get("created_at");
    assertThat(createdAtAfterInsert).isNotNull();

    ProcessingJob claimed = repository.claimNextQueued(NOW).orElseThrow();
    claimed.complete(null, NOW.plusSeconds(1));
    repository.save(claimed);

    assertThat(jobRow(queued.getId()))
        .containsEntry("created_at", createdAtAfterInsert)
        .containsEntry("status", JobStatus.COMPLETED.name());
  }

  /**
   * Two transactions may load the same job; only the first one to write may win. The aggregate
   * carries the lock version it was loaded at, so the loser is rejected instead of overwriting a
   * decision it never saw.
   */
  @Test
  void aJobLoadedBeforeSomebodyElseMovedItCannotSaveOverThem() {
    ProcessingJob queued =
        repository.save(ProcessingJob.queue(insertImportFile(), UUID.randomUUID(), NOW));
    repository.claimNextQueued(NOW).orElseThrow();

    ProcessingJob winner = repository.findById(queued.getId()).orElseThrow();
    ProcessingJob loser = repository.findById(queued.getId()).orElseThrow();
    winner.recordProgress(2, 2, 0, 2, 0, NOW.plusSeconds(1));
    repository.save(winner);

    loser.recordProgress(4, 4, 0, 4, 0, NOW.plusSeconds(2));
    assertThatThrownBy(() -> repository.save(loser))
        .isInstanceOf(OptimisticLockingFailureException.class);
    assertThat(repository.findById(queued.getId()).orElseThrow().getProcessedRows()).isEqualTo(2);
  }

  private List<Map<String, Object>> attemptRows() {
    return jdbcTemplate.queryForList(
        "SELECT id, created_at, status FROM processing_attempt ORDER BY attempt_number ASC");
  }

  private Map<String, Object> jobRow(UUID jobId) {
    return jdbcTemplate.queryForMap(
        "SELECT created_at, status FROM processing_job WHERE id = ?", jobId);
  }

  private UUID insertImportFile() {
    UUID fileId = UUID.randomUUID();
    Instant now = Instant.now();
    transactionTemplate.executeWithoutResult(
        status ->
            jdbcTemplate.update(
                """
        INSERT INTO file_import (
          id, owner_id, original_filename, storage_key, checksum_sha256, size_bytes,
          detected_content_type, retention_deadline, bucket, storage_provider,
          created_at, last_modified_at
        ) VALUES (?, ?, 'customers.csv', ?, ?, 1, 'text/csv', ?, 'file-processing', 'R2', ?, ?)
        """,
                fileId,
                UUID.randomUUID(),
                "imports/" + fileId + ".csv",
                UUID.randomUUID().toString().replace("-", "").repeat(2),
                java.sql.Timestamp.from(now.plusSeconds(3600)),
                java.sql.Timestamp.from(now),
                java.sql.Timestamp.from(now)));
    return fileId;
  }
}
