package com.vandunxg.file_processing.fileimport.application.result;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.vandunxg.file_processing.fileimport.domain.model.AttemptStatus;
import com.vandunxg.file_processing.fileimport.domain.model.AttemptTrigger;
import com.vandunxg.file_processing.fileimport.domain.model.JobStatus;

/**
 * A job's state as a caller sees it.
 *
 * <p>Composed from the job and its file, and it hides the storage key and the raw failure detail,
 * neither of which a client may see.
 */
public record ProcessingJobResult(
    UUID jobId,
    UUID fileId,
    String originalFilename,
    long sizeBytes,
    JobStatus status,
    long processedRows,
    long validRows,
    long invalidRows,
    long insertedRows,
    long updatedRows,
    Long totalRows,
    Integer progressPercent,
    int currentAttempt,
    Instant startedAt,
    Instant finishedAt,
    Instant heartbeatAt,
    Instant createdAt,
    boolean errorReportAvailable,
    String errorSummary,
    List<AttemptResult> attempts) {

  public record AttemptResult(
      int attemptNumber,
      AttemptTrigger trigger,
      AttemptStatus status,
      Instant startedAt,
      Instant finishedAt,
      long processedRows,
      long validRows,
      long invalidRows,
      long insertedRows,
      long updatedRows,
      String errorSummary) {}
}
