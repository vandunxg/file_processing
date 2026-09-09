package com.vandunxg.file_processing.fileimport.infrastructure.scheduling;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import com.vandunxg.file_processing.auth.domain.model.OperationType;
import com.vandunxg.file_processing.fileimport.application.FileImportProperties;
import com.vandunxg.file_processing.fileimport.application.capability.CustomerImportStaging;
import com.vandunxg.file_processing.fileimport.application.capability.ErrorReportStore;
import com.vandunxg.file_processing.fileimport.application.capability.FileStorage;
import com.vandunxg.file_processing.fileimport.application.service.FileImportAuditService;
import com.vandunxg.file_processing.fileimport.application.service.ProcessingJobCommandService;
import com.vandunxg.file_processing.fileimport.application.service.ProcessingJobRunner;
import com.vandunxg.file_processing.fileimport.application.service.ProcessingWorkerControl;
import com.vandunxg.file_processing.fileimport.domain.ImportFileRepository;
import com.vandunxg.file_processing.fileimport.domain.ProcessingJobRepository;
import com.vandunxg.file_processing.fileimport.domain.model.ImportFile;
import com.vandunxg.file_processing.fileimport.domain.model.JobStatus;
import com.vandunxg.file_processing.fileimport.domain.model.ProcessingJob;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the worker. Holds no business logic: it decides only when to ask the application to work.
 *
 * <p>A poll hands work to a dedicated executor and returns. It must not run the job on the calling
 * thread: Spring's scheduler is a one-thread pool by default, so a single large import would occupy
 * that thread for the length of the file and stall every other scheduled task in the application --
 * including this class's own recovery scan, the one thing that would notice a stuck worker.
 *
 * <p>Backpressure comes from the executor rejecting work rather than from a counter of jobs in
 * flight. A counter has to be incremented before submitting and decremented when the run ends, and
 * both edges are wrong: reading it in a loop condition spins, because a worker that finds an empty
 * queue decrements immediately and the poll submits again without ever returning; and a rejected
 * submission leaks the increment, so after a few rejections the instance stops claiming anything at
 * all. The pool has no queue, so a submission succeeds only when a thread is genuinely free, which
 * is exactly the signal needed and needs no bookkeeping.
 *
 * <p>Every instance polls, and the atomic claim is what keeps them from colliding.
 */
@Component
@Slf4j(topic = "PROCESSING-JOB-SCHEDULER")
public class ProcessingJobScheduler {

  private final ProcessingJobRunner processingJobRunner;
  private final ProcessingJobCommandService processingJobCommandService;
  private final ProcessingJobRepository processingJobRepository;
  private final ImportFileRepository importFileRepository;
  private final FileStorage fileStorage;
  private final ErrorReportStore errorReportStore;
  private final CustomerImportStaging customerImportStaging;
  private final FileImportProperties properties;
  private final Executor workerExecutor;
  private final ProcessingWorkerControl workerControl;
  private final ApplicationEventPublisher eventPublisher;
  private final FileImportAuditService auditService;
  private final Clock clock;

  public ProcessingJobScheduler(
      ProcessingJobRunner processingJobRunner,
      ProcessingJobCommandService processingJobCommandService,
      ProcessingJobRepository processingJobRepository,
      ImportFileRepository importFileRepository,
      FileStorage fileStorage,
      ErrorReportStore errorReportStore,
      CustomerImportStaging customerImportStaging,
      FileImportProperties properties,
      @Qualifier("fileImportWorkerExecutor") Executor workerExecutor,
      ProcessingWorkerControl workerControl,
      ApplicationEventPublisher eventPublisher,
      FileImportAuditService auditService,
      Clock clock) {
    this.processingJobRunner = processingJobRunner;
    this.processingJobCommandService = processingJobCommandService;
    this.processingJobRepository = processingJobRepository;
    this.importFileRepository = importFileRepository;
    this.fileStorage = fileStorage;
    this.errorReportStore = errorReportStore;
    this.customerImportStaging = customerImportStaging;
    this.properties = properties;
    this.workerExecutor = workerExecutor;
    this.workerControl = workerControl;
    this.eventPublisher = eventPublisher;
    this.auditService = auditService;
    this.clock = clock;
  }

  @Scheduled(
      fixedDelayString = "${app.file-import.poll-interval:1s}",
      initialDelayString = "${app.file-import.poll-interval:1s}")
  public void pollQueuedJobs() {
    if (workerControl.isStopping()) {
      return;
    }
    for (int submitted = 0; submitted < properties.workerThreads(); submitted++) {
      try {
        workerExecutor.execute(this::drainQueue);
      } catch (RejectedExecutionException rejected) {
        // Every worker thread is busy. Nothing to do until one frees up.
        return;
      } catch (RuntimeException exception) {
        // A scheduled method that throws can stop being rescheduled, and one bad poll must never
        // stop the worker.
        log.error("[poll] could not submit processing work", exception);
        return;
      }
    }
  }

  @Scheduled(
      fixedDelayString = "${app.file-import.recovery-interval:1m}",
      initialDelayString = "${app.file-import.recovery-interval:1m}")
  public void recoverAbandonedJobs() {
    try {
      Instant staleBefore = Instant.now(clock).minus(properties.staleHeartbeatThreshold());
      List<ProcessingJob> stale = processingJobRepository.findStale(staleBefore);
      for (ProcessingJob job : stale) {
        recoverOne(job);
      }
      if (!stale.isEmpty()) {
        // Declaring those attempts terminal is what makes their workspace rows abandoned. Sweeping
        // here rather than inside the transition keeps a failed PII delete from rolling the
        // transition back, and still removes the PII within this scan rather than a sweep later.
        sweepAbandonedStaging();
      }
    } catch (RuntimeException exception) {
      log.error("[recover] stale job scan failed", exception);
    }
  }

  /**
   * Removes import workspace rows that no running attempt owns.
   *
   * <p>The runner and stale-job recovery both clear their own attempt, but both do so best-effort:
   * neither may turn a failed PII deletion into a failed import. This sweep is what makes the
   * deletion eventual rather than optional, and it runs often because those rows hold customer PII.
   */
  @Scheduled(
      fixedDelayString = "${app.file-import.staging-sweep-interval:5m}",
      initialDelayString = "${app.file-import.staging-sweep-interval:5m}")
  public void sweepAbandonedStaging() {
    try {
      int removed = customerImportStaging.clearAbandoned();
      if (removed > 0) {
        log.info("[staging-sweep] removed {} abandoned workspace rows", removed);
      }
    } catch (RuntimeException exception) {
      // A scheduled method that throws stops being rescheduled, and the sweep is the last line of
      // defence for this PII -- it must come back on the next tick.
      log.error(
          "[staging-sweep] failed causeType={}", exception.getClass().getSimpleName(), exception);
    }
  }

  /** Purges retained objects but deliberately keeps the job and file metadata for audit history. */
  @Scheduled(fixedDelayString = "${app.file-import.retention-cleanup-interval:1h}")
  public void cleanExpiredObjects() {
    for (ImportFile file : importFileRepository.findExpired(Instant.now(clock))) {
      processingJobRepository
          .findByImportFileId(file.getId())
          .filter(this::isTerminal)
          .ifPresent(job -> cleanExpiredObjects(file, job));
    }
  }

  private void cleanExpiredObjects(ImportFile file, ProcessingJob job) {
    try {
      if (fileStorage.exists(file.getStorageKey())) {
        fileStorage.delete(file.getStorageKey());
        auditService.record(
            OperationType.FILE_DELETED_BY_RETENTION, file.getId(), null, Instant.now(clock));
      }
      if (job.getErrorReportKey() != null) {
        errorReportStore.discard(job.getErrorReportKey());
        auditService.record(
            OperationType.REPORT_DELETED_BY_RETENTION, job.getId(), null, Instant.now(clock));
      }
    } catch (RuntimeException exception) {
      log.warn(
          "[retention] object cleanup failed fileId={} jobId={} causeType={}",
          file.getId(),
          job.getId(),
          exception.getClass().getSimpleName());
    }
  }

  private boolean isTerminal(ProcessingJob job) {
    return job.getStatus() == JobStatus.COMPLETED
        || job.getStatus() == JobStatus.COMPLETED_WITH_ERRORS
        || job.getStatus() == JobStatus.FAILED
        || job.getStatus() == JobStatus.CANCELLED;
  }

  /**
   * Keeps running jobs while the queue has any, then lets the thread go.
   *
   * <p>Draining here rather than one job per poll means a backlog is worked through immediately
   * instead of one job per tick.
   */
  private void drainQueue() {
    try {
      while (!workerControl.isStopping() && processingJobRunner.runNextJob()) {
        // Keep going while work remains.
      }
    } catch (RuntimeException exception) {
      log.error("[poll] processing run failed", exception);
    }
  }

  /**
   * Recovers one job, tolerating a lost race.
   *
   * <p>Another instance may recover the same job first, which surfaces as an optimistic-lock
   * failure and is the correct outcome for this instance -- the job is already recovered. Anything
   * else is a real fault and must not be reported as a benign race, or a job stuck in PROCESSING
   * forever would look like normal operation in the log.
   */
  private void recoverOne(ProcessingJob job) {
    try {
      processingJobCommandService.recoverStaleJob(job.getId());
    } catch (OptimisticLockingFailureException race) {
      log.info("[recover] jobId={} already handled by another instance", job.getId());
    } catch (RuntimeException exception) {
      log.error("[recover] jobId={} could not be recovered", job.getId(), exception);
    }
  }

  @PreDestroy
  void stopAcceptingWork() {
    workerControl.requestStop();
    AvailabilityChangeEvent.publish(eventPublisher, this, ReadinessState.REFUSING_TRAFFIC);
  }
}
