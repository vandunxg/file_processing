package com.vandunxg.file_processing.fileimport.domain.model;

import java.time.Instant;
import java.util.UUID;

import com.vandunxg.common.utils.IdUtils;
import lombok.Getter;

/** Append-only execution history owned by a processing job. */
@Getter
public class ProcessingAttempt {

  private final UUID id;
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

  private ProcessingAttempt(
      UUID id,
      UUID jobId,
      int attemptNumber,
      AttemptTrigger trigger,
      AttemptStatus status,
      Instant startedAt) {
    this.id = id;
    this.jobId = jobId;
    this.attemptNumber = attemptNumber;
    this.trigger = trigger;
    this.status = status;
    this.startedAt = startedAt;
  }

  static ProcessingAttempt start(
      UUID jobId, int attemptNumber, AttemptTrigger trigger, Instant startedAt) {
    return new ProcessingAttempt(
        IdUtils.nextId(), jobId, attemptNumber, trigger, AttemptStatus.RUNNING, startedAt);
  }

  void finish(
      AttemptStatus status,
      Instant finishedAt,
      long processedRows,
      long validRows,
      long invalidRows,
      long insertedRows,
      long updatedRows,
      String errorCode,
      String errorSummary) {
    this.status = status;
    this.finishedAt = finishedAt;
    this.processedRows = processedRows;
    this.validRows = validRows;
    this.invalidRows = invalidRows;
    this.insertedRows = insertedRows;
    this.updatedRows = updatedRows;
    this.errorCode = errorCode;
    this.errorSummary = errorSummary;
  }
}
