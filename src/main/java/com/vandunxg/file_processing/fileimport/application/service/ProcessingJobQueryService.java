package com.vandunxg.file_processing.fileimport.application.service;

import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import com.vandunxg.common.models.dto.PageDTO;
import com.vandunxg.file_processing.fileimport.application.capability.ErrorReportStore;
import com.vandunxg.file_processing.fileimport.application.capability.ProcessingJobSearchRepository;
import com.vandunxg.file_processing.fileimport.application.exception.FileImportErrorCode;
import com.vandunxg.file_processing.fileimport.application.exception.FileImportException;
import com.vandunxg.file_processing.fileimport.application.mapper.ProcessingJobResultMapper;
import com.vandunxg.file_processing.fileimport.application.query.ProcessingJobSearchQuery;
import com.vandunxg.file_processing.fileimport.application.result.ProcessingJobProgressResult;
import com.vandunxg.file_processing.fileimport.application.result.ProcessingJobResult;
import com.vandunxg.file_processing.fileimport.application.result.ProcessingJobSummaryResult;
import com.vandunxg.file_processing.fileimport.domain.ImportFileRepository;
import com.vandunxg.file_processing.fileimport.domain.ProcessingJobRepository;
import com.vandunxg.file_processing.fileimport.domain.model.ImportFile;
import com.vandunxg.file_processing.fileimport.domain.model.ProcessingJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads a job on behalf of its owner or an admin. */
@Service
@RequiredArgsConstructor
@Slf4j(topic = "PROCESSING-JOB-QUERY")
public class ProcessingJobQueryService {

  private final ProcessingJobRepository processingJobRepository;
  private final ImportFileRepository importFileRepository;
  private final ProcessingJobSearchRepository processingJobSearchRepository;
  private final ErrorReportStore errorReportStore;
  private final ProcessingJobResultMapper mapper;
  private final Clock clock;

  /**
   * Lists jobs the caller is allowed to see.
   *
   * <p>The owner filter is overwritten rather than merely defaulted when the caller may only see
   * their own work: a caller who names somebody else's id must get their own jobs back, not that
   * owner's, or the filter would become a way to read another owner's list.
   */
  @Transactional(readOnly = true)
  public PageDTO<ProcessingJobSummaryResult> list(
      ProcessingJobSearchQuery query, UUID requesterId, boolean admin) {
    if (!admin) {
      query.setOwnerId(requesterId);
    }
    long total = processingJobSearchRepository.count(query);
    if (total == 0) {
      return PageDTO.of(List.of(), query.getPageIndex(), query.getPageSize(), 0);
    }
    List<ProcessingJob> jobs = processingJobSearchRepository.search(query);
    Map<UUID, ImportFile> files =
        importFileRepository
            .findAllByIds(jobs.stream().map(ProcessingJob::getImportFileId).distinct().toList())
            .stream()
            .collect(java.util.stream.Collectors.toMap(ImportFile::getId, Function.identity()));
    List<ProcessingJobSummaryResult> rows =
        jobs.stream().map(job -> mapper.toSummaryResult(job, fileOf(job, files))).toList();
    return PageDTO.of(rows, query.getPageIndex(), query.getPageSize(), total);
  }

  @Transactional(readOnly = true)
  public ProcessingJobResult get(UUID jobId, UUID ownerId, boolean admin) {
    ProcessingJob job = requireVisible(jobId, ownerId, admin);
    return mapper.toResult(job, file(job), Instant.now(clock));
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
    return openErrorReport(requireVisible(jobId, ownerId, admin));
  }

  private InputStream openErrorReport(ProcessingJob job) {
    if (!job.hasErrorReport()) {
      throw new FileImportException(FileImportErrorCode.PROCESSING_JOB_REPORT_NOT_AVAILABLE);
    }
    // Retention is the contract for how long a report is kept, so once it has passed the report is
    // gone. Reaching for the object anyway would turn an expected outcome into whatever the storage
    // SDK raises for a missing key -- a 500 carrying the key and the provider's own message.
    if (file(job).isExpired(Instant.now(clock))) {
      throw new FileImportException(FileImportErrorCode.PROCESSING_JOB_REPORT_EXPIRED);
    }
    try {
      return errorReportStore.openPublished(job.getErrorReportKey());
    } catch (RuntimeException exception) {
      // The report should be there. Whatever went wrong is the bucket's problem, not the caller's,
      // and the caller must not be told the bucket's version of it.
      log.error("[report] jobId={} could not be opened", job.getId(), exception);
      throw new FileImportException(FileImportErrorCode.FILE_IMPORT_STORAGE_UNAVAILABLE, exception);
    }
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
    // The job was reached through a file the caller may already see, so re-checking visibility
    // would only load it a second time.
    return openErrorReport(job);
  }

  /**
   * The file of a listed job.
   *
   * <p>The read model only returns jobs whose file is still live, so a miss here means the file was
   * retired between the two reads. That is an inconsistent page rather than a listable row, so it
   * fails instead of rendering a job with no filename.
   */
  private static ImportFile fileOf(ProcessingJob job, Map<UUID, ImportFile> files) {
    ImportFile file = files.get(job.getImportFileId());
    if (file == null) {
      throw new FileImportException(FileImportErrorCode.FILE_IMPORT_NOT_FOUND);
    }
    return file;
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
