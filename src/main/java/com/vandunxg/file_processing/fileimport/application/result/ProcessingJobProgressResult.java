package com.vandunxg.file_processing.fileimport.application.result;

import java.time.Instant;
import java.util.UUID;

import com.vandunxg.file_processing.fileimport.domain.model.JobStatus;

/**
 * What a client polling a running job needs, and nothing else.
 *
 * <p>{@code totalRows} and {@code progressPercent} stay null until the file has been read to the
 * end: the row count is not in the header, so it cannot be known in advance.
 */
public record ProcessingJobProgressResult(
    UUID jobId,
    JobStatus status,
    long processedRows,
    long validRows,
    long invalidRows,
    long insertedRows,
    long updatedRows,
    Long totalRows,
    Integer progressPercent,
    Instant startedAt,
    Instant heartbeatAt) {}
