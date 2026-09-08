package com.vandunxg.file_processing.fileimport.application.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.vandunxg.file_processing.fileimport.application.exception.FileImportErrorCode;
import com.vandunxg.file_processing.fileimport.application.exception.FileImportException;
import com.vandunxg.file_processing.fileimport.domain.ImportFileRepository;
import com.vandunxg.file_processing.fileimport.domain.ProcessingJobRepository;
import com.vandunxg.file_processing.fileimport.domain.exception.ProcessingJobRuleViolation;
import com.vandunxg.file_processing.fileimport.domain.model.AttemptTrigger;
import com.vandunxg.file_processing.fileimport.domain.model.ProcessingJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every state transition of a processing job, each in its own short transaction.
 *
 * <p>A run lasts as long as the file takes to read, so the job is never held open across it. Each
 * method here loads the aggregate, applies one transition and commits, which keeps a worker's
 * progress durable and leaves the row unlocked between checkpoints.
 */
@Service
@RequiredArgsConstructor
@Slf4j(topic = "PROCESSING-JOB-COMMAND")
public class ProcessingJobCommandService {

  private final ProcessingJobRepository processingJobRepository;
  private final ImportFileRepository importFileRepository;
  private final Clock clock;

  /** Takes ownership of the next queued job, or returns empty when there is nothing to do. */
  public Optional<ProcessingJob> claimNextQueued() {
    Optional<ProcessingJob> claimed = processingJobRepository.claimNextQueued(Instant.now(clock));
    claimed.ifPresent(
        job -> log.info("[claim] jobId={} attempt={}", job.getId(), job.getCurrentAttempt()));
    return claimed;
  }

  /**
   * Persists a checkpoint of a run in progress.
   *
   * <p>May fail with {@link OptimisticLockingFailureException} when someone writes to the same job
   * concurrently -- a cancellation request, typically. The caller decides what that means; for a
   * checkpoint it is tolerable.
   */
  @Transactional
  public void recordProgress(
      UUID jobId,
      long processedRows,
      long validRows,
      long invalidRows,
      long insertedRows,
      long updatedRows) {
    ProcessingJob job = require(jobId);
    job.recordProgress(
        processedRows, validRows, invalidRows, insertedRows, updatedRows, Instant.now(clock));
    processingJobRepository.save(job);
  }

  @Transactional
  public void complete(
      UUID jobId,
      long processedRows,
      long validRows,
      long invalidRows,
      long insertedRows,
      long updatedRows,
      String errorReportKey) {
    Instant now = Instant.now(clock);
    ProcessingJob job = require(jobId);
    job.recordProgress(
        processedRows, validRows, invalidRows, insertedRows, updatedRows, processedRows, now);
    if (job.isCancellationRequested()) {
      // Cancellation landed after the worker's last safe-point check. The run happens to be
      // finished, but the answer to the caller is still "cancelled", and a cancelled job publishes
      // no report -- so the key is dropped rather than completing behind the request's back.
      job.cancel(now);
      processingJobRepository.save(job);
      log.info("[complete] jobId={} cancelled after finishing its last batch", jobId);
      return;
    }
    job.complete(errorReportKey, now);
    processingJobRepository.save(job);
    log.info(
        "[complete] jobId={} status={} processed={} invalid={}",
        jobId,
        job.getStatus(),
        processedRows,
        invalidRows);
  }

  @Transactional
  public void fail(UUID jobId, String errorCode, String errorSummary) {
    ProcessingJob job = require(jobId);
    job.fail(errorCode, errorSummary, Instant.now(clock));
    processingJobRepository.save(job);
    log.warn("[fail] jobId={} errorCode={}", jobId, errorCode);
  }

  @Transactional
  public void cancel(UUID jobId) {
    ProcessingJob job = require(jobId);
    job.cancel(Instant.now(clock));
    processingJobRepository.save(job);
    log.info("[cancel] jobId={} stopped at safe point", jobId);
  }

  /** True when someone asked this job to stop. Checked by the worker between batches. */
  public boolean isCancellationRequested(UUID jobId) {
    return processingJobRepository
        .findById(jobId)
        .map(ProcessingJob::isCancellationRequested)
        .orElse(false);
  }

  /**
   * Asks a job to stop, on behalf of its owner or an admin.
   *
   * <p>A job nobody has claimed is cancelled outright; a running one only records the request,
   * because its worker has to reach a safe point first.
   */
  @Transactional
  public void requestCancellation(UUID jobId, UUID ownerId, boolean admin) {
    ProcessingJob job = requireVisible(jobId, ownerId, admin);
    try {
      switch (job.getStatus()) {
        case QUEUED -> job.cancelQueued(Instant.now(clock));
        default -> job.requestCancellation();
      }
    } catch (ProcessingJobRuleViolation violation) {
      throw new FileImportException(FileImportErrorCode.from(violation.getRule()), violation);
    }
    processingJobRepository.save(job);
    log.info("[cancel-requested] jobId={} status={}", jobId, job.getStatus());
  }

  /**
   * Requeues a terminated job so a worker picks it up again.
   *
   * <p>Refuses when the original file is gone, because a retry replays that file from the start and
   * would otherwise fail for a reason the caller cannot act on.
   */
  @Transactional
  public void requestRetry(UUID jobId, UUID ownerId, boolean admin) {
    ProcessingJob job = requireVisible(jobId, ownerId, admin);
    requireReplayableOriginal(job);
    try {
      job.requestRetry(admin ? AttemptTrigger.ADMIN_RETRY : AttemptTrigger.USER_RETRY);
    } catch (ProcessingJobRuleViolation violation) {
      throw new FileImportException(FileImportErrorCode.from(violation.getRule()), violation);
    }
    processingJobRepository.save(job);
    log.info("[retry-requested] jobId={} attemptsSoFar={}", jobId, job.getCurrentAttempt());
  }

  /**
   * Returns an abandoned job to the queue.
   *
   * <p>A crashed worker leaves its job marked as running forever. Closing the dead attempt and
   * requeueing lets another worker replay the file; batches the lost run already committed stay
   * committed, and an upsert makes replaying them harmless.
   */
  @Transactional
  public void recoverStaleJob(UUID jobId) {
    ProcessingJob job = require(jobId);
    if (!job.getStatus()
            .equals(com.vandunxg.file_processing.fileimport.domain.model.JobStatus.PROCESSING)
        && !job.isCancellationRequested()) {
      // Another scheduler instance already recovered it.
      return;
    }
    job.recoverFromStaleWorker(
        "WORKER_HEARTBEAT_LOST", "Worker stopped reporting progress", Instant.now(clock));
    processingJobRepository.save(job);
    log.warn("[recover] jobId={} requeued after lost worker status={}", jobId, job.getStatus());
  }

  private void requireReplayableOriginal(ProcessingJob job) {
    var file =
        importFileRepository
            .findById(job.getImportFileId())
            .orElseThrow(
                () ->
                    new FileImportException(FileImportErrorCode.FILE_IMPORT_ORIGINAL_FILE_EXPIRED));
    if (file.isExpired(Instant.now(clock))) {
      throw new FileImportException(FileImportErrorCode.FILE_IMPORT_ORIGINAL_FILE_EXPIRED);
    }
  }

  private ProcessingJob require(UUID jobId) {
    return processingJobRepository
        .findById(jobId)
        .orElseThrow(() -> new FileImportException(FileImportErrorCode.PROCESSING_JOB_NOT_FOUND));
  }

  /** An admin sees every job; anyone else sees only their own, and other jobs look absent. */
  private ProcessingJob requireVisible(UUID jobId, UUID ownerId, boolean admin) {
    return (admin
            ? processingJobRepository.findById(jobId)
            : processingJobRepository.findByIdAndOwnerId(jobId, ownerId))
        .orElseThrow(() -> new FileImportException(FileImportErrorCode.PROCESSING_JOB_NOT_FOUND));
  }
}
