package com.vandunxg.file_processing.fileimport.application.result;

import java.time.Instant;
import java.util.UUID;

import com.vandunxg.file_processing.fileimport.domain.model.JobStatus;

/**
 * What an accepted upload tells the caller.
 *
 * <p>Rows are not processed during the request, so there are no counters here. The caller polls the
 * job for those.
 */
public record UploadFileResult(
    UUID fileId,
    UUID jobId,
    JobStatus jobStatus,
    String originalFilename,
    long sizeBytes,
    String checksumSha256,
    Instant createdAt) {}
