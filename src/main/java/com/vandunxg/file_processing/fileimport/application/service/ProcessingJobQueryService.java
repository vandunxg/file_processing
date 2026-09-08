package com.vandunxg.file_processing.fileimport.application.service;

import java.io.InputStream;
import java.util.UUID;

import com.vandunxg.file_processing.fileimport.application.capability.ErrorReportStore;
import com.vandunxg.file_processing.fileimport.application.exception.FileImportErrorCode;
import com.vandunxg.file_processing.fileimport.application.exception.FileImportException;
import com.vandunxg.file_processing.fileimport.application.mapper.ProcessingJobResultMapper;
import com.vandunxg.file_processing.fileimport.application.result.ProcessingJobProgressResult;
import com.vandunxg.file_processing.fileimport.application.result.ProcessingJobResult;
import com.vandunxg.file_processing.fileimport.domain.ImportFileRepository;
import com.vandunxg.file_processing.fileimport.domain.ProcessingJobRepository;
import com.vandunxg.file_processing.fileimport.domain.model.ImportFile;
import com.vandunxg.file_processing.fileimport.domain.model.JobStatus;
import com.vandunxg.file_processing.fileimport.domain.model.ProcessingJob;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads a job on behalf of its owner or an admin. */
@Service
@RequiredArgsConstructor
public class ProcessingJobQueryService {

  private final ProcessingJobRepository processingJobRepository;
  private final ImportFileRepository importFileRepository;
  private final ErrorReportStore errorReportStore;
  private final ProcessingJobResultMapper mapper;

  @Transactional(readOnly = true)
  public ProcessingJobResult get(UUID jobId, UUID ownerId, boolean admin) {
    ProcessingJob job = requireVisible(jobId, ownerId, admin);
    return mapper.toResult(job, file(job));
  }

  /** Counters and timing only, so polling does not carry the file metadata and attempt history. */
  @Transactional(readOnly = true)
  public ProcessingJobProgressResult getProgress(UUID jobId, UUID ownerId, boolean admin) {
    return mapper.toProgressResult(requireVisible(jobId, ownerId, admin));
  }

  /**
   * Opens the final error report.
   *
   * <p>Only a run that finished with rejected rows has one. Asking for any other job's report is a
   * conflict rather than a not-found, because the job exists -- the report does not.
   */
  @Transactional(readOnly = true)
  public InputStream openErrorReport(UUID jobId, UUID ownerId, boolean admin) {
    ProcessingJob job = requireVisible(jobId, ownerId, admin);
    if (job.getStatus() != JobStatus.COMPLETED_WITH_ERRORS || job.getErrorReportKey() == null) {
      throw new FileImportException(FileImportErrorCode.PROCESSING_JOB_REPORT_NOT_AVAILABLE);
    }
    return errorReportStore.openPublished(job.getErrorReportKey());
  }

  /** Resolves the canonical job of a file, for the older file-scoped report route. */
  @Transactional(readOnly = true)
  public InputStream openErrorReportByFile(UUID fileId, UUID ownerId, boolean admin) {
    ImportFile file =
        (admin
                ? importFileRepository.findById(fileId)
                : importFileRepository.findByIdAndOwnerId(fileId, ownerId))
            .orElseThrow(() -> new FileImportException(FileImportErrorCode.FILE_IMPORT_NOT_FOUND));
    ProcessingJob job =
        processingJobRepository
            .findByImportFileId(file.getId())
            .orElseThrow(
                () -> new FileImportException(FileImportErrorCode.PROCESSING_JOB_NOT_FOUND));
    return openErrorReport(job.getId(), ownerId, admin);
  }

  private ImportFile file(ProcessingJob job) {
    return importFileRepository
        .findById(job.getImportFileId())
        .orElseThrow(() -> new FileImportException(FileImportErrorCode.FILE_IMPORT_NOT_FOUND));
  }

  private ProcessingJob requireVisible(UUID jobId, UUID ownerId, boolean admin) {
    return (admin
            ? processingJobRepository.findById(jobId)
            : processingJobRepository.findByIdAndOwnerId(jobId, ownerId))
        .orElseThrow(() -> new FileImportException(FileImportErrorCode.PROCESSING_JOB_NOT_FOUND));
  }
}
