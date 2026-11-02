package com.vandunxg.file_processing.fileimport.domain.model;

import java.time.Instant;
import java.util.UUID;

import com.vandunxg.common.models.domain.AuditableDomain;
import com.vandunxg.common.utils.IdUtils;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * One execution of a processing job.
 *
 * <p>History is append-only: a retry adds an attempt instead of overwriting the previous one, so
 * the record of why a job ran and how it ended survives every later run. Only the job that owns
 * this attempt may create or finish it, which is why the lifecycle methods are package-private.
 */
@Getter
@EqualsAndHashCode(callSuper = false, of = "id")
public class ProcessingAttempt extends AuditableDomain {

  private final UUID id;

  /** Lets a caller correlate an attempt with its job without navigating back up. */
  private final UUID jobId;

  private final int attemptNumber;
  private final AttemptTrigger trigger;
  private AttemptStatus status;
  private final Instant startedAt;
  private Instant finishedAt;
  private long processedRows;
  private long validRows;
  private long invalidRows;
  private long insertedRows;
  private long updatedRows;
  private String errorCode;
  private String errorSummary;
  private final Instant deletedAt;

  private ProcessingAttempt(
      UUID id,
      UUID jobId,
      int attemptNumber,
      AttemptTrigger trigger,
      AttemptStatus status,
      Instant startedAt,
      Instant deletedAt) {
    this.id = id;
    this.jobId = jobId;
    this.attemptNumber = attemptNumber;
    this.trigger = trigger;
    this.status = status;
    this.startedAt = startedAt;
    this.deletedAt = deletedAt;
  }

  static ProcessingAttempt start(
      UUID jobId, int attemptNumber, AttemptTrigger trigger, Instant startedAt) {
    return new ProcessingAttempt(
        IdUtils.nextId(), jobId, attemptNumber, trigger, AttemptStatus.RUNNING, startedAt, null);
  }

  /**
   * Rebuilds a stored attempt from persistence.
   *
   * <p>Public because the mapper that reads the row lives outside this package, but it takes every
   * persisted field, so adding one to the attempt fails to compile here until persistence carries
   * it too. {@link #start} and {@link #finish} stay package-private: reconstitution replays a fact,
   * while driving the lifecycle remains the owning job's alone.
   */
  public static ProcessingAttempt reconstitute(
      UUID id,
      UUID jobId,
      int attemptNumber,
      AttemptTrigger trigger,
      AttemptStatus status,
      Instant startedAt,
      Instant finishedAt,
      RowCounters counters,
      String errorCode,
      String errorSummary,
      Instant deletedAt,
      String createdBy,
      Instant createdAt,
      String lastModifiedBy,
      Instant lastModifiedAt) {
    ProcessingAttempt attempt =
        new ProcessingAttempt(id, jobId, attemptNumber, trigger, status, startedAt, deletedAt);
    attempt.finishedAt = finishedAt;
    attempt.processedRows = counters.processedRows();
    attempt.validRows = counters.validRows();
    attempt.invalidRows = counters.invalidRows();
    attempt.insertedRows = counters.insertedRows();
    attempt.updatedRows = counters.updatedRows();
    attempt.errorCode = errorCode;
    attempt.errorSummary = errorSummary;
    attempt.setCreatedBy(createdBy);
    attempt.setCreatedAt(createdAt);
    attempt.setLastModifiedBy(lastModifiedBy);
    attempt.setLastModifiedAt(lastModifiedAt);
    return attempt;
  }

  void finish(
      AttemptStatus status,
      Instant finishedAt,
      RowCounters counters,
      String errorCode,
      String errorSummary) {
    this.status = status;
    this.finishedAt = finishedAt;
    this.processedRows = counters.processedRows();
    this.validRows = counters.validRows();
    this.invalidRows = counters.invalidRows();
    this.insertedRows = counters.insertedRows();
    this.updatedRows = counters.updatedRows();
    this.errorCode = errorCode;
    this.errorSummary = errorSummary;
  }
}
