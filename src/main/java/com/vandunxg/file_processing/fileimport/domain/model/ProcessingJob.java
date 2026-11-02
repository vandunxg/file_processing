package com.vandunxg.file_processing.fileimport.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.vandunxg.common.models.domain.AuditableDomain;
import com.vandunxg.common.utils.IdUtils;
import com.vandunxg.file_processing.fileimport.domain.exception.ProcessingJobRule;
import com.vandunxg.file_processing.fileimport.domain.exception.ProcessingJobRuleViolation;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * Aggregate root for a file's processing lifecycle.
 *
 * <p>This is the only place that decides what processing is allowed and records what happened.
 * {@link ImportFile} says which file was accepted and never changes; everything that moves --
 * state, counters, progress, retries, cancellation -- lives here.
 *
 * <p>Carries no persistence mapping: how these facts are stored is {@code ProcessingJobEntity}'s
 * business, not this state machine's. Mutation happens only through the behaviour below, never
 * through setters.
 */
@Getter
@EqualsAndHashCode(callSuper = false, of = "id")
public class ProcessingJob extends AuditableDomain {

  /** Attempts a user or admin may ask for. The first, automatic attempt does not count. */
  private static final long MAX_REQUESTED_RETRIES = 3;

  private UUID id;

  private UUID importFileId;

  private UUID ownerId;

  private JobStatus status;

  private long processedRows;

  private long validRows;

  private long invalidRows;

  private long insertedRows;

  private long updatedRows;

  /**
   * Unknown until the file has been read to the end, because the row count is not in the header.
   */
  private Long totalRows;

  private Integer progressPercent;

  private int currentAttempt;

  private Instant startedAt;

  private Instant finishedAt;

  private Instant heartbeatAt;

  private String errorReportKey;

  private String errorCode;

  private String errorSummary;

  /**
   * Why the next attempt will run. Decided when a retry or recovery is requested, consumed when a
   * worker claims the job, so it has to outlive a restart in between.
   */
  private AttemptTrigger nextAttemptTrigger;

  private Instant deletedAt;

  private Long version;

  /** The job's whole history, oldest attempt first. */
  private List<ProcessingAttempt> attempts = new ArrayList<>();

  private ProcessingJob(UUID id, UUID importFileId, UUID ownerId, Instant createdAt) {
    this.id = id;
    this.importFileId = importFileId;
    this.ownerId = ownerId;
    this.status = JobStatus.QUEUED;
    this.nextAttemptTrigger = AttemptTrigger.INITIAL;
    setCreatedAt(createdAt);
  }

  public static ProcessingJob queue(UUID importFileId, UUID ownerId, Instant createdAt) {
    return new ProcessingJob(IdUtils.nextId(), importFileId, ownerId, createdAt);
  }

  /**
   * Rebuilds a stored job from persistence.
   *
   * <p>Queueing invariants are not re-checked: they were enforced when the row was written, and a
   * stored row is a fact rather than a new request. Every persisted field is a parameter, so adding
   * one to the aggregate fails to compile here until persistence carries it too.
   *
   * <p>{@code version} is carried so a write can be rejected when the row moved underneath it, and
   * {@code deletedAt} so a retired job is rebuilt as retired rather than being resurrected by the
   * next write.
   */
  public static ProcessingJob reconstitute(
      UUID id,
      UUID importFileId,
      UUID ownerId,
      JobStatus status,
      RowCounters counters,
      Long totalRows,
      Integer progressPercent,
      int currentAttempt,
      Instant startedAt,
      Instant finishedAt,
      Instant heartbeatAt,
      String errorReportKey,
      String errorCode,
      String errorSummary,
      AttemptTrigger nextAttemptTrigger,
      Instant deletedAt,
      Long version,
      List<ProcessingAttempt> attempts,
      String createdBy,
      Instant createdAt,
      String lastModifiedBy,
      Instant lastModifiedAt) {
    ProcessingJob job = new ProcessingJob(id, importFileId, ownerId, createdAt);
    job.status = status;
    job.processedRows = counters.processedRows();
    job.validRows = counters.validRows();
    job.invalidRows = counters.invalidRows();
    job.insertedRows = counters.insertedRows();
    job.updatedRows = counters.updatedRows();
    job.totalRows = totalRows;
    job.progressPercent = progressPercent;
    job.currentAttempt = currentAttempt;
    job.startedAt = startedAt;
    job.finishedAt = finishedAt;
    job.heartbeatAt = heartbeatAt;
    job.errorReportKey = errorReportKey;
    job.errorCode = errorCode;
    job.errorSummary = errorSummary;
    job.nextAttemptTrigger = nextAttemptTrigger;
    job.deletedAt = deletedAt;
    job.version = version;
    job.attempts = new ArrayList<>(attempts);
    job.setCreatedBy(createdBy);
    job.setLastModifiedBy(lastModifiedBy);
    job.setLastModifiedAt(lastModifiedAt);
    return job;
  }

  /**
   * Takes the job for execution and opens the attempt that will report on it.
   *
   * <p>Only a queued job can be claimed. That is what stops a second worker from taking a job that
   * is already running; the repository still has to make the read-and-claim atomic.
   */
  public void claim(Instant now) {
    if (status != JobStatus.QUEUED) {
      throw new ProcessingJobRuleViolation(ProcessingJobRule.ONLY_QUEUED_JOB_CAN_BE_CLAIMED);
    }
    currentAttempt += 1;
    attempts.add(ProcessingAttempt.start(id, currentAttempt, nextAttemptTrigger, now));
    status = JobStatus.PROCESSING;
    startedAt = now;
    heartbeatAt = now;
  }

  /** Cancels before any worker has taken the job, so there is no attempt to close. */
  public void cancelQueued(Instant now) {
    if (status != JobStatus.QUEUED) {
      throw new ProcessingJobRuleViolation(ProcessingJobRule.ONLY_QUEUED_JOB_CAN_BE_CANCELLED);
    }
    status = JobStatus.CANCELLED;
    finishedAt = now;
  }

  /**
   * Asks the worker to stop. Cancellation is cooperative: the worker finishes its current batch and
   * stops at a safe point, so this only records the request.
   */
  public void requestCancellation() {
    if (status == JobStatus.CANCELLATION_REQUESTED) {
      return;
    }
    if (status != JobStatus.PROCESSING) {
      throw new ProcessingJobRuleViolation(ProcessingJobRule.JOB_NOT_CANCELLABLE);
    }
    status = JobStatus.CANCELLATION_REQUESTED;
  }

  /**
   * Records a checkpoint of the run in progress.
   *
   * <p>The state check runs before the totals are judged, so a caller writing to a job that is no
   * longer running is told that and not something about arithmetic: whether the job still belongs
   * to it is the part it can act on.
   */
  public void recordProgress(RowCounters counters, Instant heartbeatAt) {
    if (!hasRunningAttempt()) {
      throw new ProcessingJobRuleViolation(ProcessingJobRule.ONLY_RUNNING_JOB_CAN_RECORD_PROGRESS);
    }
    counters.requireConsistent();
    if (counters.processedRows() < this.processedRows) {
      throw new ProcessingJobRuleViolation(ProcessingJobRule.PROGRESS_CANNOT_DECREASE);
    }
    this.processedRows = counters.processedRows();
    this.validRows = counters.validRows();
    this.invalidRows = counters.invalidRows();
    this.insertedRows = counters.insertedRows();
    this.updatedRows = counters.updatedRows();
    this.heartbeatAt = heartbeatAt;
  }

  /** Progress once the file has been read to the end and the row count is known. */
  public void recordProgress(RowCounters counters, long totalRows, Instant heartbeatAt) {
    recordProgress(counters, heartbeatAt);
    this.totalRows = totalRows;
    this.progressPercent = totalRows == 0 ? 100 : (int) ((processedRows * 100) / totalRows);
  }

  /**
   * Ends a successful run.
   *
   * <p>A report exists only when rows were rejected, and rejected rows always produce one, so the
   * report key and the invalid-row count must agree.
   */
  public void complete(String errorReportKey, Instant finishedAt) {
    if (status != JobStatus.PROCESSING) {
      throw new ProcessingJobRuleViolation(ProcessingJobRule.ONLY_PROCESSING_JOB_CAN_COMPLETE);
    }
    if ((invalidRows == 0) != (errorReportKey == null || errorReportKey.isBlank())) {
      throw new ProcessingJobRuleViolation(ProcessingJobRule.REPORT_AVAILABILITY_MISMATCH);
    }
    status = invalidRows == 0 ? JobStatus.COMPLETED : JobStatus.COMPLETED_WITH_ERRORS;
    this.errorReportKey = errorReportKey;
    this.finishedAt = finishedAt;
    currentAttempt().finish(AttemptStatus.SUCCEEDED, finishedAt, counters(), null, null);
  }

  /** Ends a run that hit a system failure. Rejected rows are not a failure -- they complete. */
  public void fail(String errorCode, String errorSummary, Instant finishedAt) {
    if (!hasRunningAttempt()) {
      throw new ProcessingJobRuleViolation(ProcessingJobRule.ONLY_RUNNING_JOB_CAN_FAIL);
    }
    status = JobStatus.FAILED;
    this.errorReportKey = null;
    this.errorCode = errorCode;
    this.errorSummary = errorSummary;
    this.finishedAt = finishedAt;
    currentAttempt().finish(AttemptStatus.FAILED, finishedAt, counters(), errorCode, errorSummary);
  }

  /**
   * Requeues a job for another run, keeping the same job and file and every earlier attempt.
   *
   * <p>Runtime counters reset because the next attempt reads the file from the beginning and
   * reports its own totals. No attempt is created yet: that happens when a worker claims the job,
   * so a requeued job never holds an attempt nobody is executing.
   */
  public void requestRetry(AttemptTrigger trigger) {
    if (status != JobStatus.FAILED && status != JobStatus.CANCELLED) {
      throw new ProcessingJobRuleViolation(ProcessingJobRule.JOB_NOT_RETRYABLE);
    }
    if (isRequestedByPerson(trigger) && requestedRetries() >= MAX_REQUESTED_RETRIES) {
      throw new ProcessingJobRuleViolation(ProcessingJobRule.RETRY_LIMIT_EXCEEDED);
    }
    nextAttemptTrigger = trigger;
    status = JobStatus.QUEUED;
    processedRows = 0;
    validRows = 0;
    invalidRows = 0;
    insertedRows = 0;
    updatedRows = 0;
    totalRows = null;
    progressPercent = null;
    startedAt = null;
    finishedAt = null;
    heartbeatAt = null;
    errorReportKey = null;
    errorCode = null;
    errorSummary = null;
  }

  /** Closes the job at the safe point the worker reached after cancellation was requested. */
  public void cancel(Instant finishedAt) {
    if (status != JobStatus.CANCELLATION_REQUESTED) {
      throw new ProcessingJobRuleViolation(ProcessingJobRule.CANCELLATION_MUST_BE_REQUESTED_FIRST);
    }
    status = JobStatus.CANCELLED;
    this.finishedAt = finishedAt;
    currentAttempt().finish(AttemptStatus.CANCELLED, finishedAt, counters(), null, null);
  }

  public boolean isCancellationRequested() {
    return status == JobStatus.CANCELLATION_REQUESTED;
  }

  public List<ProcessingAttempt> getAttempts() {
    return List.copyOf(attempts);
  }

  /**
   * Whether asking to cancel would change anything.
   *
   * <p>These three read the same conditions the transitions below enforce, so a client is never
   * offered an action that would come back as a conflict. They exist here rather than in the
   * application because the answer is the state machine's, not a view's.
   */
  public boolean isCancellable() {
    return status == JobStatus.QUEUED || status == JobStatus.PROCESSING;
  }

  /** Whether a person could ask for another attempt, budget included. */
  public boolean isRetryable() {
    return (status == JobStatus.FAILED || status == JobStatus.CANCELLED)
        && requestedRetries() < MAX_REQUESTED_RETRIES;
  }

  /** Whether a report of rejected rows exists to download. */
  public boolean hasErrorReport() {
    return status == JobStatus.COMPLETED_WITH_ERRORS && errorReportKey != null;
  }

  private long requestedRetries() {
    return attempts.stream().filter(attempt -> isRequestedByPerson(attempt.getTrigger())).count();
  }

  private static boolean isRequestedByPerson(AttemptTrigger trigger) {
    return trigger == AttemptTrigger.USER_RETRY || trigger == AttemptTrigger.ADMIN_RETRY;
  }

  private boolean hasRunningAttempt() {
    return status == JobStatus.PROCESSING || status == JobStatus.CANCELLATION_REQUESTED;
  }

  /** The job's totals as they stand, for the attempt that is reporting them. */
  private RowCounters counters() {
    return RowCounters.builder()
        .processedRows(processedRows)
        .validRows(validRows)
        .invalidRows(invalidRows)
        .insertedRows(insertedRows)
        .updatedRows(updatedRows)
        .build();
  }

  private ProcessingAttempt currentAttempt() {
    return attempts.getLast();
  }
}
