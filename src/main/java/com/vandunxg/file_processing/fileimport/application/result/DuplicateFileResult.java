package com.vandunxg.file_processing.fileimport.application.result;

import java.time.Instant;
import java.util.UUID;

import com.vandunxg.file_processing.fileimport.domain.model.JobStatus;

/** Canonical resource details returned with a duplicate-upload conflict. */
public record DuplicateFileResult(
    UUID fileId, UUID jobId, JobStatus jobStatus, Instant uploadedAt, String errorCode) {}
