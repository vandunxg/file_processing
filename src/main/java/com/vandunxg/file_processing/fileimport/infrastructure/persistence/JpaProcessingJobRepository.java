package com.vandunxg.file_processing.fileimport.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.vandunxg.file_processing.fileimport.domain.ProcessingJobRepository;
import com.vandunxg.file_processing.fileimport.domain.model.ProcessingJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The job aggregate is mapped directly to its table, so one Spring Data interface satisfies the
 * domain contract and no separate persistence model or mapper exists.
 */
public interface JpaProcessingJobRepository
    extends JpaRepository<ProcessingJob, UUID>,
        ProcessingJobRepository,
        ProcessingJobRepositoryCustom {

  @Override
  Optional<ProcessingJob> findByIdAndOwnerId(UUID id, UUID ownerId);

  /**
   * A job still marked as running whose worker stopped sending heartbeats. A job that has never
   * been claimed has no heartbeat and is not stale -- it is simply waiting.
   */
  @Override
  @Query(
      """
      SELECT job FROM ProcessingJob job
      WHERE job.status IN (
              com.vandunxg.file_processing.fileimport.domain.model.JobStatus.PROCESSING,
              com.vandunxg.file_processing.fileimport.domain.model.JobStatus.CANCELLATION_REQUESTED)
        AND job.deletedAt IS NULL
        AND job.heartbeatAt IS NOT NULL
        AND job.heartbeatAt < :heartbeatBefore
      ORDER BY job.heartbeatAt ASC
      """)
  List<ProcessingJob> findStale(@Param("heartbeatBefore") Instant heartbeatBefore);
}
