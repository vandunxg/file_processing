package com.vandunxg.file_processing.fileimport.infrastructure.scheduling;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import com.vandunxg.file_processing.fileimport.application.FileImportProperties;
import com.vandunxg.file_processing.fileimport.application.service.ProcessingJobCommandService;
import com.vandunxg.file_processing.fileimport.application.service.ProcessingJobRunner;
import com.vandunxg.file_processing.fileimport.domain.ProcessingJobRepository;
import com.vandunxg.file_processing.fileimport.domain.model.ProcessingJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the worker. Holds no business logic: it decides only when to ask the application to work.
 *
 * <p>A poll hands the job to a dedicated executor and returns immediately. It must not run the job
 * itself: Spring's scheduler is a one-thread pool by default, so a single large import would sit on
 * that thread for the length of the file and stall every other scheduled task in the application --
 * including this class's own recovery scan, which is what would otherwise notice a stuck worker.
 *
 * <p>In-flight work is counted so the executor is never handed more jobs than it has threads, which
 * keeps the queue from growing without bound.
 *
 * <p>Every instance polls, and the atomic claim is what keeps them from colliding. Recovery is
 * idempotent, so several instances inspecting the same abandoned job is safe.
 */
@Component
@Slf4j(topic = "PROCESSING-JOB-SCHEDULER")
public class ProcessingJobScheduler {

  private final ProcessingJobRunner processingJobRunner;
  private final ProcessingJobCommandService processingJobCommandService;
  private final ProcessingJobRepository processingJobRepository;
  private final FileImportProperties properties;
  private final Executor workerExecutor;
  private final Clock clock;
  private final AtomicInteger inFlight = new AtomicInteger();

  public ProcessingJobScheduler(
      ProcessingJobRunner processingJobRunner,
      ProcessingJobCommandService processingJobCommandService,
      ProcessingJobRepository processingJobRepository,
      FileImportProperties properties,
      @Qualifier("fileImportWorkerExecutor") Executor workerExecutor,
      Clock clock) {
    this.processingJobRunner = processingJobRunner;
    this.processingJobCommandService = processingJobCommandService;
    this.processingJobRepository = processingJobRepository;
    this.properties = properties;
    this.workerExecutor = workerExecutor;
    this.clock = clock;
  }

  @Scheduled(
      fixedDelayString = "${app.file-import.poll-interval:1s}",
      initialDelayString = "${app.file-import.poll-interval:1s}")
  public void pollQueuedJobs() {
    try {
      while (inFlight.get() < properties.workerThreads()) {
        inFlight.incrementAndGet();
        workerExecutor.execute(this::runOneJob);
      }
    } catch (RuntimeException exception) {
      // A scheduled method that throws can stop being rescheduled, and one bad poll must never
      // stop the worker.
      log.error("[poll] could not submit processing work", exception);
    }
  }

  @Scheduled(
      fixedDelayString = "${app.file-import.stale-heartbeat-threshold:5m}",
      initialDelayString = "${app.file-import.stale-heartbeat-threshold:5m}")
  public void recoverAbandonedJobs() {
    try {
      Instant staleBefore = Instant.now(clock).minus(properties.staleHeartbeatThreshold());
      List<ProcessingJob> stale = processingJobRepository.findStale(staleBefore);
      for (ProcessingJob job : stale) {
        recoverOne(job);
      }
    } catch (RuntimeException exception) {
      log.error("[recover] stale job scan failed", exception);
    }
  }

  private void runOneJob() {
    try {
      processingJobRunner.runNextJob();
    } catch (RuntimeException exception) {
      log.error("[poll] processing run failed", exception);
    } finally {
      inFlight.decrementAndGet();
    }
  }

  /**
   * Recovers one job, tolerating a lost race.
   *
   * <p>Another instance may recover the same job first, which surfaces as an optimistic-lock
   * failure. That is the correct outcome for this instance -- the job is already recovered -- so it
   * must not abort the rest of the scan.
   */
  private void recoverOne(ProcessingJob job) {
    try {
      processingJobCommandService.recoverStaleJob(job.getId());
    } catch (RuntimeException exception) {
      log.info("[recover] jobId={} already handled elsewhere", job.getId(), exception);
    }
  }
}
