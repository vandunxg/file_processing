package com.vandunxg.file_processing.fileimport.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.vandunxg.file_processing.fileimport.application.capability.ProcessingJobSearchRepository;
import com.vandunxg.file_processing.fileimport.application.query.ProcessingJobSearchQuery;
import com.vandunxg.file_processing.fileimport.domain.ProcessingJobRepository;
import com.vandunxg.file_processing.fileimport.domain.model.JobStatus;
import com.vandunxg.file_processing.fileimport.domain.model.ProcessingJob;
import com.vandunxg.file_processing.testsupport.AuthIntegrationTestBase;
import com.vandunxg.file_processing.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/** The read model behind the job list: every filter, and everything it must refuse to show. */
@PostgresIntegrationTest
class JpaProcessingJobSearchRepositoryIT extends AuthIntegrationTestBase {

  private static final Instant NOW = Instant.parse("2026-09-08T12:00:00Z");

  @Autowired private ProcessingJobSearchRepository searchRepository;
  @Autowired private ProcessingJobRepository jobRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private TransactionTemplate transactionTemplate;

  @BeforeEach
  void resetProcessingTables() {
    transactionTemplate.executeWithoutResult(
        status -> {
          jdbcTemplate.update("DELETE FROM processing_attempt");
          jdbcTemplate.update("DELETE FROM processing_job");
          jdbcTemplate.update("DELETE FROM file_import");
        });
  }

  @Test
  void listsOnlyTheJobsOfTheOwnerTheQueryNames() {
    UUID owner = UUID.randomUUID();
    UUID mine = queue(owner, "mine.csv", NOW);
    queue(UUID.randomUUID(), "theirs.csv", NOW);

    ProcessingJobSearchQuery query = page().ownerId(owner).build();

    assertThat(searchRepository.count(query)).isEqualTo(1L);
    assertThat(searchRepository.search(query))
        .extracting(ProcessingJob::getId)
        .containsExactly(mine);
  }

  @Test
  void listsEveryOwnerWhenNoOwnerIsNamed() {
    queue(UUID.randomUUID(), "one.csv", NOW);
    queue(UUID.randomUUID(), "two.csv", NOW);

    assertThat(searchRepository.count(page().build())).isEqualTo(2L);
  }

  @Test
  void filtersByStatus() {
    UUID owner = UUID.randomUUID();
    queue(owner, "queued.csv", NOW);
    UUID claimed = queue(owner, "running.csv", NOW);
    ProcessingJob job = jobRepository.findById(claimed).orElseThrow();
    job.claim(NOW);
    jobRepository.save(job);

    ProcessingJobSearchQuery query = page().status(JobStatus.PROCESSING).build();

    assertThat(searchRepository.search(query))
        .extracting(ProcessingJob::getId)
        .containsExactly(claimed);
  }

  @Test
  void filtersByCreationTimeInclusivelyOnBothBounds() {
    UUID owner = UUID.randomUUID();
    UUID older = queue(owner, "older.csv", NOW.minusSeconds(7200));
    UUID onTheBound = queue(owner, "bound.csv", NOW.minusSeconds(3600));
    UUID newer = queue(owner, "newer.csv", NOW);

    List<UUID> found =
        searchRepository
            .search(
                page()
                    .createdFrom(NOW.minusSeconds(3600))
                    .createdTo(NOW)
                    .sortBy("createdAt.asc")
                    .build())
            .stream()
            .map(ProcessingJob::getId)
            .toList();

    assertThat(found).containsExactly(onTheBound, newer).doesNotContain(older);
  }

  @Test
  void matchesTheFilenameKeywordPartiallyAndIgnoringCase() {
    UUID owner = UUID.randomUUID();
    UUID wanted = queue(owner, "Q3-Customers-EU.csv", NOW);
    queue(owner, "suppliers.csv", NOW);

    assertThat(searchRepository.search(page().keyword("customers").build()))
        .extracting(ProcessingJob::getId)
        .containsExactly(wanted);
  }

  @Test
  void hidesARetiredJob() {
    UUID owner = UUID.randomUUID();
    UUID retired = queue(owner, "retired.csv", NOW);
    queue(owner, "live.csv", NOW);
    softDelete("processing_job", retired);

    assertThat(searchRepository.count(page().build())).isEqualTo(1L);
    assertThat(searchRepository.search(page().build()))
        .extracting(ProcessingJob::getId)
        .doesNotContain(retired);
  }

  @Test
  void hidesAJobWhoseFileHasBeenRetired() {
    UUID owner = UUID.randomUUID();
    UUID orphaned = queue(owner, "gone.csv", NOW);
    softDelete("file_import", fileIdOf(orphaned));

    // The list describes a file being imported. With the file retired there is no filename, no
    // size, and nothing a caller could act on, so count and rows must agree that it is gone.
    assertThat(searchRepository.count(page().build())).isZero();
    assertThat(searchRepository.search(page().build())).isEmpty();
  }

  @Test
  void ordersNewestFirstByDefaultAndPagesThroughTheResult() {
    UUID owner = UUID.randomUUID();
    UUID oldest = queue(owner, "1.csv", NOW.minusSeconds(120));
    UUID middle = queue(owner, "2.csv", NOW.minusSeconds(60));
    UUID newest = queue(owner, "3.csv", NOW);

    ProcessingJobSearchQuery firstPage =
        ProcessingJobSearchQuery.builder().pageIndex(1).pageSize(2).ownerId(owner).build();
    ProcessingJobSearchQuery secondPage =
        ProcessingJobSearchQuery.builder().pageIndex(2).pageSize(2).ownerId(owner).build();

    assertThat(searchRepository.count(firstPage)).isEqualTo(3L);
    assertThat(searchRepository.search(firstPage))
        .extracting(ProcessingJob::getId)
        .containsExactly(newest, middle);
    assertThat(searchRepository.search(secondPage))
        .extracting(ProcessingJob::getId)
        .containsExactly(oldest);
  }

  private static ProcessingJobSearchQuery.ProcessingJobSearchQueryBuilder<?, ?> page() {
    return ProcessingJobSearchQuery.builder().pageIndex(1).pageSize(20);
  }

  /** Queues a job over a freshly stored file and backdates both rows to {@code createdAt}. */
  private UUID queue(UUID ownerId, String filename, Instant createdAt) {
    UUID fileId = insertImportFile(ownerId, filename, createdAt);
    ProcessingJob job = jobRepository.save(ProcessingJob.queue(fileId, ownerId, createdAt));
    // Auditing stamps created_at on insert, so the only way to test a time range is to write it.
    transactionTemplate.executeWithoutResult(
        status ->
            jdbcTemplate.update(
                "UPDATE processing_job SET created_at = ? WHERE id = ?",
                Timestamp.from(createdAt),
                job.getId()));
    return job.getId();
  }

  private UUID insertImportFile(UUID ownerId, String filename, Instant createdAt) {
    UUID fileId = UUID.randomUUID();
    transactionTemplate.executeWithoutResult(
        status ->
            jdbcTemplate.update(
                """
                INSERT INTO file_import (
                  id, owner_id, original_filename, storage_key, checksum_sha256, size_bytes,
                  detected_content_type, retention_deadline, bucket, storage_provider,
                  created_at, last_modified_at
                ) VALUES (?, ?, ?, ?, ?, 1, 'text/csv', ?, 'file-processing', 'R2', ?, ?)
                """,
                fileId,
                ownerId,
                filename,
                "imports/" + fileId + ".csv",
                UUID.randomUUID().toString().replace("-", "").repeat(2),
                Timestamp.from(createdAt.plusSeconds(3600)),
                Timestamp.from(createdAt),
                Timestamp.from(createdAt)));
    return fileId;
  }

  private UUID fileIdOf(UUID jobId) {
    return jobRepository.findById(jobId).orElseThrow().getImportFileId();
  }

  private void softDelete(String table, UUID id) {
    transactionTemplate.executeWithoutResult(
        status ->
            jdbcTemplate.update(
                "UPDATE " + table + " SET deleted_at = ? WHERE id = ?", Timestamp.from(NOW), id));
  }
}
