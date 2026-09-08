package com.vandunxg.file_processing.fileimport.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.vandunxg.file_processing.fileimport.domain.ProcessingJobRepository;
import com.vandunxg.file_processing.fileimport.domain.model.ProcessingJob;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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

  /**
   * Business reads exclude soft-deleted jobs.
   *
   * <p>Declared explicitly rather than derived, because {@code findById} is inherited from Spring
   * Data and would otherwise return a retired job -- which would then stay readable, cancellable
   * and, through a retry that requeues it, processable again, bypassing the claim query's own
   * filter.
   */
  @Override
  @Query("SELECT job FROM ProcessingJob job WHERE job.id = :id AND job.deletedAt IS NULL")
  Optional<ProcessingJob> findById(@Param("id") UUID id);

  @Override
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT job FROM ProcessingJob job WHERE job.id = :id AND job.deletedAt IS NULL")
  Optional<ProcessingJob> findByIdForUpdate(@Param("id") UUID id);

  @Override
  @Query(
      """
      SELECT job FROM ProcessingJob job
      WHERE job.id = :id AND job.ownerId = :ownerId AND job.deletedAt IS NULL
      """)
  Optional<ProcessingJob> findByIdAndOwnerId(@Param("id") UUID id, @Param("ownerId") UUID ownerId);

  @Override
  @Query(
      """
      SELECT job FROM ProcessingJob job
      WHERE job.importFileId = :importFileId AND job.deletedAt IS NULL
      """)
  Optional<ProcessingJob> findByImportFileId(@Param("importFileId") UUID importFileId);

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
