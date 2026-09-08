package com.vandunxg.file_processing.fileimport.application.result;

import java.time.Instant;
import java.util.UUID;

import com.vandunxg.file_processing.fileimport.domain.model.JobStatus;

/**
 * One row of the job list.
 *
 * <p>Deliberately narrower than {@link ProcessingJobResult}: a list shows what a caller needs to
 * pick a job, so the attempt history and the sanitized failure detail stay on the detail view and a
 * page of rows costs one query for the jobs and one for their files.
 */
public record ProcessingJobSummaryResult(
    UUID jobId,
    UUID fileId,
    UUID ownerId,
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
    Instant createdAt,
    boolean errorReportAvailable) {}
