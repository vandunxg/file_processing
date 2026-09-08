package com.vandunxg.file_processing.fileimport.infrastructure.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import com.vandunxg.file_processing.fileimport.domain.model.ProcessingJob;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

/**
 * Claims queued work without two workers ever taking the same job.
 *
 * <p>Reading a candidate and updating it afterwards would let a second worker read the same row in
 * the gap. {@code FOR UPDATE SKIP LOCKED} instead hands each caller a row nobody else holds: the
 * winner locks it until commit and every other caller skips straight past it and finds nothing,
 * rather than blocking or failing. The state transition itself stays in the aggregate, so the job
 * state machine exists in exactly one place.
 */
@RequiredArgsConstructor
public class ProcessingJobRepositoryCustomImpl implements ProcessingJobRepositoryCustom {

  private static final String LOCK_NEXT_QUEUED =
      """
      SELECT * FROM processing_job
      WHERE status = 'QUEUED' AND deleted_at IS NULL
      ORDER BY created_at ASC
      FOR UPDATE SKIP LOCKED
      LIMIT 1
      """;

  private final EntityManager entityManager;

  @Override
  @Transactional
  public Optional<ProcessingJob> claimNextQueued(Instant now) {
    @SuppressWarnings("unchecked")
    List<ProcessingJob> locked =
        entityManager.createNativeQuery(LOCK_NEXT_QUEUED, ProcessingJob.class).getResultList();
    return locked.stream()
        .findFirst()
        .map(
            job -> {
              job.claim(now);
              entityManager.flush();
              return job;
            });
  }
}
