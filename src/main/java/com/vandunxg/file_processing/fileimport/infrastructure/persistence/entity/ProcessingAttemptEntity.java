package com.vandunxg.file_processing.fileimport.infrastructure.persistence.entity;

import java.time.Instant;
import java.util.UUID;

import com.vandunxg.common.models.entities.AuditableEntity;
import com.vandunxg.file_processing.fileimport.domain.model.AttemptStatus;
import com.vandunxg.file_processing.fileimport.domain.model.AttemptTrigger;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One row of a job's attempt history.
 *
 * <p>The owning job writes the join column, so {@code jobId} is read-only here and exists only so a
 * mapped attempt can name its job without navigating back up.
 */
@Entity
@Table(name = "processing_attempt")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = false, of = "id")
public class ProcessingAttemptEntity extends AuditableEntity {

  @Id
  @Column(name = "id")
  private UUID id;

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
}
