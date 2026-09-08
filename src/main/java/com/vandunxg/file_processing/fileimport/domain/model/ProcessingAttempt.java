package com.vandunxg.file_processing.fileimport.domain.model;

import java.time.Instant;
import java.util.UUID;

import com.vandunxg.common.models.entities.AuditableEntity;
import com.vandunxg.common.utils.IdUtils;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;

/**
 * One execution of a processing job.
 *
 * <p>History is append-only: a retry adds an attempt instead of overwriting the previous one, so
 * the record of why a job ran and how it ended survives every later run. Only the job that owns
 * this attempt may create or finish it, which is why the lifecycle methods are package-private.
 */
@Entity
@Table(name = "processing_attempt")
@Getter
@EqualsAndHashCode(callSuper = false, of = "id")
public class ProcessingAttempt extends AuditableEntity {

  @Id
  @Column(name = "id")
  private UUID id;

  /**
   * Written through the owning job's join column, so it is read-only here and exists only to let
   * callers correlate an attempt with its job without navigating back up.
   */
  @Column(name = "job_id", nullable = false, insertable = false, updatable = false)
  private UUID jobId;

  @Column(name = "attempt_number", nullable = false)
  private int attemptNumber;

  @Enumerated(EnumType.STRING)
  @Column(name = "\"trigger\"", nullable = false, length = 30)
  private AttemptTrigger trigger;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", nullable = false, length = 30)
  private AttemptStatus status;

  @Column(name = "started_at", nullable = false)
  private Instant startedAt;

  @Column(name = "finished_at")
  private Instant finishedAt;

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

  @Column(name = "error_code", length = 100)
  private String errorCode;

  @Column(name = "error_summary", length = 500)
  private String errorSummary;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  protected ProcessingAttempt() {
    // Hibernate.
  }

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
