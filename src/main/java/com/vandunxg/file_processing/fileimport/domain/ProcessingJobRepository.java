package com.vandunxg.file_processing.fileimport.domain;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.vandunxg.file_processing.fileimport.domain.model.ProcessingJob;

/** Aggregate repository for the processing lifecycle of an import file. */
public interface ProcessingJobRepository {

  ProcessingJob save(ProcessingJob job);

  Optional<ProcessingJob> findById(UUID id);

  /**
   * Loads a job only when the caller is allowed to see it, so a caller asking for someone else's
   * job cannot tell it apart from one that does not exist.
   */
  Optional<ProcessingJob> findByIdAndOwnerId(UUID id, UUID ownerId);

  /**
   * Takes exclusive ownership of the oldest queued job, or returns empty when none is available.
   *
   * <p>Concurrent workers must never both take the same job, so the implementation claims and locks
   * in one atomic step rather than reading a candidate and updating it afterwards.
   */
  Optional<ProcessingJob> claimNextQueued(Instant now);

  /** Jobs a worker still owns on paper but has stopped reporting progress for. */
  java.util.List<ProcessingJob> findStale(Instant heartbeatBefore);
}
