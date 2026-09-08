package com.vandunxg.file_processing.fileimport.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.vandunxg.common.models.entities.AuditableEntity;
import com.vandunxg.common.utils.IdUtils;
import com.vandunxg.file_processing.fileimport.domain.exception.ProcessingJobRule;
import com.vandunxg.file_processing.fileimport.domain.exception.ProcessingJobRuleViolation;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * Aggregate root for a file's processing lifecycle.
 *
 * <p>This is the only place that decides what processing is allowed and records what happened.
 * {@link ImportFile} says which file was accepted and never changes; everything that moves --
 * state, counters, progress, retries, cancellation -- lives here.
 *
 * <p>Mapped directly to its table: the schema was designed around this shape, so a separate
 * persistence model would only duplicate it. Mutation still happens through the behaviour below,
 * never through setters.
 */
@Entity
@Table(name = "processing_job")
@Getter
@EqualsAndHashCode(callSuper = false, of = "id")
public class ProcessingJob extends AuditableEntity {

  /** Attempts a user or admin may ask for. The first, automatic attempt does not count. */
  private static final long MAX_REQUESTED_RETRIES = 3;

  /**
   * Automatic requeues after a lost worker. Recovery is not something anyone asked for, so it does
   * not consume the retry budget above -- but it still needs a ceiling, or a job that kills every
   * worker that touches it would be requeued forever.
   */
  private static final long MAX_RECOVERIES = 3;

  @Id
  @Column(name = "id")
  private UUID id;

  @Column(name = "import_file_id", nullable = false, updatable = false)
  private UUID importFileId;

  @Column(name = "owner_id", nullable = false, updatable = false)
  private UUID ownerId;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 30)
  private JobStatus status;

  @Column(name = "processed_rows", nullable = false)
  private long processedRows;

  @Column(name = "valid_rows", nullable = false)
  private long validRows;

  @Column(name = "invalid_rows", nullable = false)
  private long invalidRows;

  @Column(name = "inserted_rows", nullable = false)
  private long insertedRows;

  @Column(name = "updated_rows", nullable = false)
  private long updatedRows;

  /**
   * Unknown until the file has been read to the end, because the row count is not in the header.
   */
  @Column(name = "total_rows")
  private Long totalRows;

  @Column(name = "progress_percent")
  private Integer progressPercent;

  @Column(name = "current_attempt", nullable = false)
  private int currentAttempt;

  @Column(name = "started_at")
  private Instant startedAt;

  @Column(name = "finished_at")
  private Instant finishedAt;

  @Column(name = "heartbeat_at")
  private Instant heartbeatAt;

  @Column(name = "error_report_key", length = 512)
  private String errorReportKey;

  @Column(name = "error_code", length = 100)
  private String errorCode;

  @Column(name = "error_summary", length = 500)
  private String errorSummary;

  /**
   * Why the next attempt will run. Decided when a retry or recovery is requested, consumed when a
   * worker claims the job, so it has to outlive a restart in between.
   */
  @Enumerated(EnumType.STRING)
  @Column(name = "next_attempt_trigger", nullable = false, length = 30)
  private AttemptTrigger nextAttemptTrigger;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  @Version
  @Column(name = "version", nullable = false)
  private Long version;

  @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER)
  @JoinColumn(name = "job_id", nullable = false)
  @OrderBy("attemptNumber ASC")
  private List<ProcessingAttempt> attempts = new ArrayList<>();

  protected ProcessingJob() {
    // Hibernate.
  }

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

  public void recordProgress(
      long processedRows,
      long validRows,
      long invalidRows,
      long insertedRows,
      long updatedRows,
      Instant heartbeatAt) {
    if (!hasRunningAttempt()) {
      throw new ProcessingJobRuleViolation(ProcessingJobRule.ONLY_RUNNING_JOB_CAN_RECORD_PROGRESS);
    }
    if (processedRows < 0
        || validRows < 0
        || invalidRows < 0
        || insertedRows < 0
        || updatedRows < 0
        || processedRows != validRows + invalidRows
        || insertedRows + updatedRows > validRows) {
      throw new ProcessingJobRuleViolation(ProcessingJobRule.INVALID_COUNTERS);
    }
    if (processedRows < this.processedRows) {
      throw new ProcessingJobRuleViolation(ProcessingJobRule.PROGRESS_CANNOT_DECREASE);
    }
    this.processedRows = processedRows;
    this.validRows = validRows;
    this.invalidRows = invalidRows;
    this.insertedRows = insertedRows;
    this.updatedRows = updatedRows;
    this.heartbeatAt = heartbeatAt;
  }

  /** Progress once the file has been read to the end and the row count is known. */
  public void recordProgress(
      long processedRows,
      long validRows,
      long invalidRows,
      long insertedRows,
      long updatedRows,
      long totalRows,
      Instant heartbeatAt) {
    recordProgress(processedRows, validRows, invalidRows, insertedRows, updatedRows, heartbeatAt);
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
    currentAttempt()
        .finish(
            AttemptStatus.SUCCEEDED,
            finishedAt,
            processedRows,
            validRows,
            invalidRows,
            insertedRows,
            updatedRows,
            null,
            null);
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
    currentAttempt()
        .finish(
            AttemptStatus.FAILED,
            finishedAt,
            processedRows,
            validRows,
            invalidRows,
            insertedRows,
            updatedRows,
            errorCode,
            errorSummary);
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
    currentAttempt()
        .finish(
            AttemptStatus.CANCELLED,
            finishedAt,
            processedRows,
            validRows,
            invalidRows,
            insertedRows,
            updatedRows,
            null,
            null);
  }

  /**
   * Closes a run whose worker disappeared, then requeues the job when another run is still allowed.
   *
   * <p>Recovery does not count against the retry limit -- nobody asked for it -- and the batches
   * the lost run already committed stay committed.
   */
  public void recoverFromStaleWorker(String errorCode, String errorSummary, Instant now) {
    fail(errorCode, errorSummary, now);
    if (recoveries() >= MAX_RECOVERIES) {
      // Left failed rather than requeued. A person can still retry it deliberately, which is the
      // right outcome for a job that has already taken down three workers.
      return;
    }
    requestRetry(AttemptTrigger.RECOVERY);
  }

  public boolean isCancellationRequested() {
    return status == JobStatus.CANCELLATION_REQUESTED;
  }

  public List<ProcessingAttempt> getAttempts() {
    return List.copyOf(attempts);
  }

  private long recoveries() {
    return attempts.stream()
        .filter(attempt -> attempt.getTrigger() == AttemptTrigger.RECOVERY)
        .count();
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

  private ProcessingAttempt currentAttempt() {
    return attempts.getLast();
  }
}
