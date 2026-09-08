package com.vandunxg.file_processing.fileimport.infrastructure.scheduling;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import com.vandunxg.file_processing.fileimport.application.FileImportProperties;
import com.vandunxg.file_processing.fileimport.application.service.ProcessingJobCommandService;
import com.vandunxg.file_processing.fileimport.application.service.ProcessingJobRunner;
import com.vandunxg.file_processing.fileimport.domain.ProcessingJobRepository;
import com.vandunxg.file_processing.fileimport.domain.model.ProcessingJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the worker. Holds no business logic: it decides only when to ask the application to work.
 *
 * <p>Every instance polls, and the atomic claim is what keeps them from colliding. Recovery runs on
 * the same schedule and is idempotent, so several instances inspecting the same abandoned job is
 * safe.
 */
@Component
@RequiredArgsConstructor
@Slf4j(topic = "PROCESSING-JOB-SCHEDULER")
public class ProcessingJobScheduler {

  private final ProcessingJobRunner processingJobRunner;
  private final ProcessingJobCommandService processingJobCommandService;
  private final ProcessingJobRepository processingJobRepository;
  private final FileImportProperties properties;
  private final Clock clock;

  @Scheduled(
      fixedDelayString = "${app.file-import.poll-interval:1s}",
      initialDelayString = "${app.file-import.poll-interval:1s}")
  public void pollQueuedJobs() {
    try {
      for (int drained = 0; drained < properties.maxJobsPerPoll(); drained++) {
        if (!processingJobRunner.runNextJob()) {
          return;
        }
      }
    } catch (RuntimeException exception) {
      // A scheduled method that throws can stop being rescheduled, and one bad job must never stop
      // the worker.
      log.error("[poll] processing poll failed", exception);
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
        processingJobCommandService.recoverStaleJob(job.getId());
      }
    } catch (RuntimeException exception) {
      log.error("[recover] stale job scan failed", exception);
    }
  }
}
