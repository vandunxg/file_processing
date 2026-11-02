package com.vandunxg.file_processing.fileimport.infrastructure.persistence.entity;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.vandunxg.common.models.entities.AuditableEntity;
import com.vandunxg.file_processing.fileimport.domain.model.AttemptTrigger;
import com.vandunxg.file_processing.fileimport.domain.model.JobStatus;
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
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;

/**
 * The processing job's row, and the only place its table shape is named.
 *
 * <p>This mirrors the aggregate field for field on purpose: the schema was designed around that
 * shape, so the mapper between them is mechanical and stays that way. What lives here rather than
 * on the aggregate is everything the row needs and the business rule does not -- the column names,
 * the lock version, and how the attempt history is fetched.
 *
 * <p>It also serves as the paging sort model: {@code @ValidatePaging} builds its allow-list by
 * reflecting over {@code @Column} fields, so the api names this class for that one purpose.
 */
@Entity
@Table(name = "processing_job")
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(callSuper = false, of = "id")
public class ProcessingJobEntity extends AuditableEntity {

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

  @Enumerated(EnumType.STRING)
  @Column(name = "next_attempt_trigger", nullable = false, length = 30)
  private AttemptTrigger nextAttemptTrigger;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  @Version
  @Column(name = "version", nullable = false)
  private Long version;

  /**
   * The whole history loads with the job, because an aggregate is loaded whole and the retry limit
   * bounds how many attempts there can be.
   *
   * <p>Batched because the job list reads a page of jobs at once, and initialising this collection
   * one job at a time would make a page cost a query per row.
   */
  @OneToMany(cascade = CascadeType.ALL, fetch = FetchType.EAGER)
  @JoinColumn(name = "job_id", nullable = false)
  @BatchSize(size = 100)
  @OrderBy("attemptNumber ASC")
  private List<ProcessingAttemptEntity> attempts = new ArrayList<>();
}
