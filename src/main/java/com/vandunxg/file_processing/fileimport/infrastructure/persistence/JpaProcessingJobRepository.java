package com.vandunxg.file_processing.fileimport.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.vandunxg.file_processing.fileimport.application.capability.ProcessingJobSearchRepository;
import com.vandunxg.file_processing.fileimport.application.query.ProcessingJobSearchQuery;
import com.vandunxg.file_processing.fileimport.domain.ProcessingJobRepository;
import com.vandunxg.file_processing.fileimport.domain.model.ProcessingJob;
import com.vandunxg.file_processing.fileimport.infrastructure.persistence.entity.ProcessingJobEntity;
import com.vandunxg.file_processing.fileimport.infrastructure.persistence.mapper.ProcessingJobPersistenceMapper;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores the job aggregate.
 *
 * <p>A write merges the mapped row over the stored one, so the lock version the aggregate carries
 * decides whether it is still allowed to win: a job loaded in an earlier transaction and saved
 * after somebody else moved the row is rejected rather than silently overwriting it.
 *
 * <p>Serves two contracts. The aggregate's own repository loads and stores whole jobs; the job list
 * is a read model and speaks {@code PagingQuery}, an application type, so it is declared on an
 * application capability instead of the domain contract. One class satisfies both, which is why no
 * second implementation exists.
 */
@Repository
@RequiredArgsConstructor
public class JpaProcessingJobRepository
    implements ProcessingJobRepository, ProcessingJobSearchRepository {

  /**
   * Claims queued work without two workers ever taking the same job.
   *
   * <p>Reading a candidate and updating it afterwards would let a second worker read the same row
   * in the gap. {@code FOR UPDATE SKIP LOCKED} instead hands each caller a row nobody else holds:
   * the winner locks it until commit and every other caller skips straight past it and finds
   * nothing, rather than blocking or failing.
   */
  private static final String LOCK_NEXT_QUEUED =
      """
      SELECT * FROM processing_job
      WHERE status = 'QUEUED' AND deleted_at IS NULL
      ORDER BY created_at ASC
      FOR UPDATE SKIP LOCKED
      LIMIT 1
      """;

  private final ProcessingJobEntityRepository entityRepository;
  private final ProcessingJobPersistenceMapper mapper;
  private final EntityManager entityManager;

  @Override
  public ProcessingJob save(ProcessingJob job) {
    return mapper.toDomain(entityRepository.saveAndFlush(mapper.toEntity(job)));
  }

  @Override
  public Optional<ProcessingJob> findById(UUID id) {
    return entityRepository.findActiveById(id).map(mapper::toDomain);
  }

  @Override
  public Optional<ProcessingJob> findByIdForUpdate(UUID id) {
    return entityRepository.findActiveByIdForUpdate(id).map(mapper::toDomain);
  }

  @Override
  public Optional<ProcessingJob> findByIdAndOwnerId(UUID id, UUID ownerId) {
    return entityRepository.findActiveByIdAndOwnerId(id, ownerId).map(mapper::toDomain);
  }

  @Override
  public Optional<ProcessingJob> findByImportFileId(UUID importFileId) {
    return entityRepository.findActiveByImportFileId(importFileId).map(mapper::toDomain);
  }

  /**
   * The state transition itself stays in the aggregate, so the job state machine exists in exactly
   * one place; this only holds the row still while that transition is applied.
   */
  @Override
  @Transactional
  public Optional<ProcessingJob> claimNextQueued(Instant now) {
    @SuppressWarnings("unchecked")
    List<ProcessingJobEntity> locked =
        entityManager
            .createNativeQuery(LOCK_NEXT_QUEUED, ProcessingJobEntity.class)
            .getResultList();
    return locked.stream()
        .findFirst()
        .map(
            entity -> {
              ProcessingJob job = mapper.toDomain(entity);
              job.claim(now);
              return save(job);
            });
  }

  @Override
  public List<ProcessingJob> findStale(Instant heartbeatBefore) {
    return mapper.toDomain(entityRepository.findStale(heartbeatBefore));
  }

  @Override
  public Long count(ProcessingJobSearchQuery query) {
    return entityRepository.count(query);
  }

  @Override
  public List<ProcessingJob> search(ProcessingJobSearchQuery query) {
    return mapper.toDomain(entityRepository.search(query));
  }
}
