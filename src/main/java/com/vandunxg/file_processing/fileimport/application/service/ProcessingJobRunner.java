package com.vandunxg.file_processing.fileimport.application.service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.vandunxg.file_processing.customer.application.command.ImportCustomerBatchCommand;
import com.vandunxg.file_processing.customer.application.command.ImportCustomerRow;
import com.vandunxg.file_processing.customer.application.result.ImportCustomerBatchResult;
import com.vandunxg.file_processing.customer.application.service.CustomerImportService;
import com.vandunxg.file_processing.fileimport.application.FileImportProperties;
import com.vandunxg.file_processing.fileimport.application.capability.CustomerCsvReader;
import com.vandunxg.file_processing.fileimport.application.capability.ErrorReportStore;
import com.vandunxg.file_processing.fileimport.application.capability.FileStorage;
import com.vandunxg.file_processing.fileimport.application.exception.CsvFormatException;
import com.vandunxg.file_processing.fileimport.application.validation.NormalizedCustomerRow;
import com.vandunxg.file_processing.fileimport.application.validation.ValidatedCustomerRow;
import com.vandunxg.file_processing.fileimport.domain.ImportFileRepository;
import com.vandunxg.file_processing.fileimport.domain.model.ImportFile;
import com.vandunxg.file_processing.fileimport.domain.model.ProcessingJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * Runs one claimed job from the stored file to a terminal state.
 *
 * <p>Deliberately not transactional. The file may hold a million rows, so wrapping the run in one
 * transaction would hold a connection for its whole duration and lose every committed row if the
 * last batch failed. Instead each customer batch commits on its own and progress is checkpointed
 * separately, which is what lets a failure halfway through keep the work already done and lets a
 * retry replay the file harmlessly.
 *
 * <p>Rows stream through one batch at a time: the file is never read into memory, and the batch
 * list is the only buffer. Processing is sequential because one atomic upsert per batch already
 * meets the throughput requirement, and concurrency here would buy little while making cancellation
 * and failure handling much harder to reason about.
 */
@Service
@RequiredArgsConstructor
@Slf4j(topic = "PROCESSING-JOB-RUNNER")
public class ProcessingJobRunner {

  private final ProcessingJobCommandService processingJobCommandService;
  private final ImportFileRepository importFileRepository;
  private final FileStorage fileStorage;
  private final CustomerCsvReader customerCsvReader;
  private final ErrorReportStore errorReportStore;
  private final CustomerImportService customerImportService;
  private final FileImportProperties properties;
  private final Clock clock;

  /** Runs the next queued job if there is one. Returns false when the queue is empty. */
  public boolean runNextJob() {
    return processingJobCommandService
        .claimNextQueued()
        .map(
            job -> {
              run(job);
              return true;
            })
        .orElse(false);
  }

  private void run(ProcessingJob job) {
    Counters counters = new Counters();
    try {
      ImportFile file =
          importFileRepository
              .findById(job.getImportFileId())
              .orElseThrow(() -> new IllegalStateException("Import file is missing"));
      Outcome outcome = process(job, file, counters);
      finish(job, counters, outcome);
    } catch (CsvFormatException exception) {
      // The specific code matters: a row-cap breach is permanent, and reporting every parse problem
      // as one generic failure sends the user to retry a file that can never succeed.
      failQuietly(job, exception.code().name(), "The file could not be parsed", exception);
    } catch (RuntimeException | IOException exception) {
      failQuietly(job, "PROCESSING_FAILED", "Processing stopped on a system error", exception);
    }
  }

  /**
   * Applies the terminal transition, retrying once if the job changed underneath.
   *
   * <p>A cancellation request can commit between the transition's own read and its flush. Letting
   * that surface as a system error would report an import that inserted every customer as FAILED,
   * so the transition is re-applied against the newer state, where it resolves to a cancellation.
   */
  private void finish(ProcessingJob job, Counters counters, Outcome outcome) {
    try {
      applyTerminalState(job, counters, outcome);
    } catch (OptimisticLockingFailureException conflict) {
      log.info("[run] jobId={} changed while finishing, re-reading", job.getId());
      applyTerminalState(job, counters, outcome);
    }
  }

  private void applyTerminalState(ProcessingJob job, Counters counters, Outcome outcome) {
    if (outcome.cancelled()) {
      processingJobCommandService.cancel(job.getId());
      return;
    }
    processingJobCommandService.complete(
        job.getId(),
        counters.processedRows,
        counters.validRows,
        counters.invalidRows,
        counters.insertedRows,
        counters.updatedRows,
        outcome.reportKey());
  }

  private Outcome process(ProcessingJob job, ImportFile file, Counters counters)
      throws IOException {
    Checkpoint checkpoint = new Checkpoint(Instant.now(clock));
    List<ImportCustomerRow> batch = new ArrayList<>(properties.batchSize());

    try (InputStream input = fileStorage.open(file.getStorageKey());
        CustomerCsvReader.Run rows = customerCsvReader.open(input);
        ErrorReportStore.Draft report = errorReportStore.open(job.getId())) {
      while (true) {
        var next = rows.next();
        if (next.isEmpty()) {
          break;
        }
        ValidatedCustomerRow validated = next.orElseThrow();
        counters.processedRows++;

        if (validated.row().isEmpty()) {
          // A rejected row is a business outcome, not a failure: it is reported and the run
          // continues. Its issues all belong to one source row, so invalidRows counts the row once.
          counters.invalidRows++;
          validated.issues().forEach(issue -> report.write(issue, validated.originalRow()));
        } else {
          counters.validRows++;
          batch.add(toImportRow(validated.row().orElseThrow()));
        }

        if (batch.size() >= properties.batchSize()) {
          flush(job, batch, counters);
        }
        // Checked per row rather than per batch: a file of nothing but rejected rows never fills a
        // batch, and it would otherwise run to the end without a single heartbeat -- long enough
        // for
        // the recovery scan to declare its worker dead and requeue a job that is still running.
        if (checkpoint.isDue(counters.processedRows, Instant.now(clock))) {
          flush(job, batch, counters);
          boolean stopRequested = persistProgress(job, counters);
          checkpoint.reset(counters.processedRows, Instant.now(clock));
          if (stopRequested) {
            // Safe point: the pending batch is committed and nothing is half-written.
            return Outcome.stoppedByCancellation();
          }
        }
      }
      flush(job, batch, counters);
      if (persistProgress(job, counters)) {
        // Checked before publishing rather than after: a cancelled job drops the report key, so
        // uploading first would leave an object in the bucket that nothing references.
        return Outcome.stoppedByCancellation();
      }
      // Only a run that reached end of file publishes its report, so a cancelled or failed attempt
      // never leaves a partial report looking like the final one.
      return Outcome.finished(report.publish());
    }
  }

  private void flush(ProcessingJob job, List<ImportCustomerRow> batch, Counters counters) {
    if (batch.isEmpty()) {
      return;
    }
    ImportCustomerBatchResult result =
        customerImportService.importBatch(new ImportCustomerBatchCommand(job.getId(), batch));
    counters.insertedRows += result.insertedRows();
    counters.updatedRows += result.updatedRows();
    batch.clear();
  }

  /**
   * Checkpoints progress, tolerating a lost race.
   *
   * <p>A cancellation request writes to the same row, so a checkpoint can lose. Letting that
   * propagate would abort the run and mark the job FAILED, turning a cancellation into a failure.
   * The totals are advisory between checkpoints and the next one reports them anyway -- and the
   * cancellation check immediately after this will see why the write lost.
   */
  /** Returns true when the job has been asked to stop. */
  private boolean persistProgress(ProcessingJob job, Counters counters) {
    try {
      return processingJobCommandService.recordProgress(
          job.getId(),
          counters.processedRows,
          counters.validRows,
          counters.invalidRows,
          counters.insertedRows,
          counters.updatedRows);
    } catch (OptimisticLockingFailureException conflict) {
      log.debug("[run] jobId={} checkpoint skipped, job changed concurrently", job.getId());
      // The write that won was most likely the cancellation request itself.
      return processingJobCommandService.isCancellationRequested(job.getId());
    }
  }

  private void failQuietly(ProcessingJob job, String code, String summary, Exception cause) {
    // The message is sanitized on purpose: a raw driver or SDK error can carry connection strings,
    // credentials or row content, and it ends up in an API response.
    log.error("[run] jobId={} failed code={}", job.getId(), code, cause);
    try {
      processingJobCommandService.fail(job.getId(), code, summary);
    } catch (RuntimeException failure) {
      log.error("[run] jobId={} could not be marked failed", job.getId(), failure);
    }
  }

  private static ImportCustomerRow toImportRow(NormalizedCustomerRow row) {
    return new ImportCustomerRow(
        row.externalId(),
        row.fullName(),
        row.email(),
        row.phone(),
        row.dateOfBirth(),
        row.address());
  }

  private record Outcome(boolean cancelled, String reportKey) {

    static Outcome stoppedByCancellation() {
      return new Outcome(true, null);
    }

    static Outcome finished(String reportKey) {
      return new Outcome(false, reportKey);
    }
  }

  /** Persisting progress per row would dominate the run, so it is paced by rows and by time. */
  private final class Checkpoint {

    private long lastRows;
    private Instant lastAt;

    private Checkpoint(Instant startedAt) {
      this.lastAt = startedAt;
    }

    private boolean isDue(long processedRows, Instant now) {
      return processedRows - lastRows >= properties.progressRowInterval()
          || Duration.between(lastAt, now).compareTo(properties.progressTimeInterval()) >= 0;
    }

    private void reset(long processedRows, Instant now) {
      lastRows = processedRows;
      lastAt = now;
    }
  }

  private static final class Counters {

    private long processedRows;
    private long validRows;
    private long invalidRows;
    private long insertedRows;
    private long updatedRows;
  }
}
