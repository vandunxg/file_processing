package com.vandunxg.file_processing.fileimport.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.vandunxg.file_processing.fileimport.infrastructure.persistence.entity.ProcessingJobEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data access to the job rows.
 *
 * <p>Every finder here says {@code Active} in its name and filters {@code deletedAt IS NULL}. That
 * is deliberate: {@code findById} is inherited from Spring Data and would return a retired job --
 * which would then stay readable, cancellable and, through a retry that requeues it, processable
 * again, bypassing the claim query's own filter. Naming the filtered finders differently keeps the
 * unfiltered inherited one from being reached for by accident.
 */
public interface ProcessingJobEntityRepository
    extends JpaRepository<ProcessingJobEntity, UUID>, ProcessingJobEntityRepositoryCustom {

  @Query("SELECT job FROM ProcessingJobEntity job WHERE job.id = :id AND job.deletedAt IS NULL")
  Optional<ProcessingJobEntity> findActiveById(@Param("id") UUID id);

  /**
   * Loads a live job under an exclusive lock for a short state transition such as stale-worker
   * recovery. The caller must recheck its premise after acquiring the lock.
   */
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT job FROM ProcessingJobEntity job WHERE job.id = :id AND job.deletedAt IS NULL")
  Optional<ProcessingJobEntity> findActiveByIdForUpdate(@Param("id") UUID id);

  @Query(
      """
      SELECT job FROM ProcessingJobEntity job
      WHERE job.id = :id AND job.ownerId = :ownerId AND job.deletedAt IS NULL
      """)
  Optional<ProcessingJobEntity> findActiveByIdAndOwnerId(
      @Param("id") UUID id, @Param("ownerId") UUID ownerId);

  @Query(
      """
      SELECT job FROM ProcessingJobEntity job
      WHERE job.importFileId = :importFileId AND job.deletedAt IS NULL
      """)
  Optional<ProcessingJobEntity> findActiveByImportFileId(@Param("importFileId") UUID importFileId);

  /**
   * A job still marked as running whose worker stopped sending heartbeats. A job that has never
   * been claimed has no heartbeat and is not stale -- it is simply waiting.
   */
  @Query(
      """
      SELECT job FROM ProcessingJobEntity job
      WHERE job.status IN (
              com.vandunxg.file_processing.fileimport.domain.model.JobStatus.PROCESSING,
              com.vandunxg.file_processing.fileimport.domain.model.JobStatus.CANCELLATION_REQUESTED)
        AND job.deletedAt IS NULL
        AND job.heartbeatAt IS NOT NULL
        AND job.heartbeatAt < :heartbeatBefore
      ORDER BY job.heartbeatAt ASC
      """)
  List<ProcessingJobEntity> findStale(@Param("heartbeatBefore") Instant heartbeatBefore);
}
