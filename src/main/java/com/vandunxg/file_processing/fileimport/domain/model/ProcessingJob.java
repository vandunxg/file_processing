package com.vandunxg.file_processing.fileimport.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.vandunxg.common.models.domain.AuditableDomain;
import com.vandunxg.common.utils.IdUtils;
import com.vandunxg.file_processing.fileimport.domain.exception.ProcessingJobRule;
import com.vandunxg.file_processing.fileimport.domain.exception.ProcessingJobRuleViolation;
import lombok.Getter;

/** Aggregate root for a file's processing lifecycle. */
@Getter
public class ProcessingJob extends AuditableDomain {

  private final UUID id;
  private final UUID importFileId;
  private final UUID ownerId;
  private JobStatus status;
  private long processedRows;
  private long validRows;
  private long invalidRows;
  private long insertedRows;
  private long updatedRows;
  private Long totalRows;
  private Integer progressPercent;
  private int currentAttempt;
  private Instant startedAt;
  private Instant finishedAt;
  private Instant heartbeatAt;
  private String errorReportKey;
  private String errorCode;
  private String errorSummary;
  private AttemptTrigger nextAttemptTrigger;
  private final List<ProcessingAttempt> attempts = new ArrayList<>();

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

  public void cancelQueued(Instant now) {
    if (status != JobStatus.QUEUED) {
      throw new ProcessingJobRuleViolation(ProcessingJobRule.ONLY_QUEUED_JOB_CAN_BE_CANCELLED);
    }
    status = JobStatus.CANCELLED;
  }

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
    this.progressPercent = (int) ((processedRows * 100) / totalRows);
  }

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

  public void requestRetry(AttemptTrigger trigger) {
    if (status != JobStatus.FAILED && status != JobStatus.CANCELLED) {
      throw new ProcessingJobRuleViolation(ProcessingJobRule.JOB_NOT_RETRYABLE);
    }
    if ((trigger == AttemptTrigger.USER_RETRY || trigger == AttemptTrigger.ADMIN_RETRY)
        && attempts.stream()
                .filter(
                    attempt ->
                        attempt.getTrigger() == AttemptTrigger.USER_RETRY
                            || attempt.getTrigger() == AttemptTrigger.ADMIN_RETRY)
                .count()
            >= 3) {
      throw new ProcessingJobRuleViolation(ProcessingJobRule.RETRY_LIMIT_EXCEEDED);
    }
    nextAttemptTrigger = trigger;
    status = JobStatus.QUEUED;
    processedRows = 0;
    validRows = 0;
    invalidRows = 0;
    insertedRows = 0;
    updatedRows = 0;
    startedAt = null;
    finishedAt = null;
    heartbeatAt = null;
    errorReportKey = null;
    errorCode = null;
    errorSummary = null;
  }

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

  public List<ProcessingAttempt> getAttempts() {
    return List.copyOf(attempts);
  }

  private boolean hasRunningAttempt() {
    return status == JobStatus.PROCESSING || status == JobStatus.CANCELLATION_REQUESTED;
  }

  private ProcessingAttempt currentAttempt() {
    return attempts.getLast();
  }
}
