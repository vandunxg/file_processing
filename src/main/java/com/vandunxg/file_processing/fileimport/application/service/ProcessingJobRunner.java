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
import com.vandunxg.file_processing.fileimport.application.capability.CustomerImportStaging;
import com.vandunxg.file_processing.fileimport.application.capability.ErrorReportStore;
import com.vandunxg.file_processing.fileimport.application.capability.FileStorage;
import com.vandunxg.file_processing.fileimport.application.command.StagedCustomerRow;
import com.vandunxg.file_processing.fileimport.application.exception.CsvFormatException;
import com.vandunxg.file_processing.fileimport.application.result.StagedReportRow;
import com.vandunxg.file_processing.fileimport.application.result.StagingResolution;
import com.vandunxg.file_processing.fileimport.application.validation.NormalizedCustomerRow;
import com.vandunxg.file_processing.fileimport.application.validation.ValidatedCustomerRow;
import com.vandunxg.file_processing.fileimport.domain.ImportFileRepository;
import com.vandunxg.file_processing.fileimport.domain.exception.ProcessingJobRuleViolation;
import com.vandunxg.file_processing.fileimport.domain.model.ImportFile;
import com.vandunxg.file_processing.fileimport.domain.model.ProcessingJob;
import com.vandunxg.file_processing.fileimport.domain.model.RowCounters;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * Runs one claimed job from the stored file to a terminal state.
 *
 * <p>Deliberately not transactional. The file may hold a million rows, so wrapping the run in one
 * transaction would hold a connection for its whole duration and lose every committed row if the
 * last batch failed. Instead staging batches and later canonical-customer batches each commit on
 * their own, while progress is checkpointed separately. A failure during merge keeps earlier merge
 * batches, and a retry can safely rebuild staging from the immutable original file.
 *
 * <p>Rows stream through one staging batch at a time: the file is never read into memory, and the
 * batch list is the only buffer. After EOF, PostgreSQL decides duplicate external IDs set-wise as
 * each page is read, so no phase has to hold the whole file -- in heap or in one long transaction
 * -- to know which rows are canonical. Processing is sequential because one atomic upsert per batch
 * already meets the throughput requirement, and concurrency here would buy little while making
 * cancellation and failure handling much harder to reason about.
 *
 * <p>Every phase after EOF checkpoints on the same clock as the parse loop. A phase that works in
 * silence for longer than the stale-heartbeat threshold is declared lost while it is still running,
 * and recovery then clears the workspace underneath it.
 */
@Service
@RequiredArgsConstructor
@Slf4j(topic = "PROCESSING-JOB-RUNNER")
public class ProcessingJobRunner {

  private final ProcessingJobCommandService processingJobCommandService;
  private final ImportFileRepository importFileRepository;
  private final FileStorage fileStorage;
  private final CustomerCsvReader customerCsvReader;
  private final CustomerImportStaging customerImportStaging;
  private final ErrorReportStore errorReportStore;
  private final CustomerImportService customerImportService;
  private final ProcessingWorkerControl workerControl;
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
      checkpointBeforeFailure(job, counters);
      failQuietly(job, exception.code().name(), "The file could not be parsed", exception);
    } catch (RuntimeException | IOException exception) {
      checkpointBeforeFailure(job, counters);
      failQuietly(job, "PROCESSING_FAILED", "Processing stopped on a system error", exception);
    } finally {
      clearStaging(job);
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
    if (outcome.shutdown()) {
      // Leave the in-flight attempt and heartbeat intact. Stale recovery owns the terminal
      // WORKER_LOST transition after a forced shutdown, rather than pretending the run completed.
      return;
    }
    log.debug(
        "[run] completing jobId={} processed={} valid={} invalid={} inserted={} updated={}",
        job.getId(),
        counters.processedRows,
        counters.validRows,
        counters.invalidRows,
        counters.insertedRows,
        counters.updatedRows);
    processingJobCommandService.complete(job.getId(), counters.snapshot(), outcome.reportKey());
  }

  private Outcome process(ProcessingJob job, ImportFile file, Counters counters)
      throws IOException {
    Checkpoint checkpoint = new Checkpoint(Instant.now(clock));
    List<StagedCustomerRow> batch = new ArrayList<>(properties.batchSize());
    customerImportStaging.clear(job.getId(), job.getCurrentAttempt());

    try (InputStream input = fileStorage.open(file.getStorageKey());
        CustomerCsvReader.Run rows = customerCsvReader.open(input)) {
      while (true) {
        var next = rows.next();
        if (next.isEmpty()) {
          break;
        }
        ValidatedCustomerRow validated = next.orElseThrow();
        counters.processedRows++;

        if (validated.row().isEmpty()) {
          // Field-invalid rows are retained in staging only long enough to produce a source-ordered
          // report. Duplicate validation is resolved after the stream reaches EOF.
          counters.invalidRows++;
        } else {
          counters.validRows++;
        }
        batch.add(
            new StagedCustomerRow(validated.originalRow(), validated.row(), validated.issues()));

        if (batch.size() >= properties.batchSize()) {
          stage(job, batch);
          Outcome stop = stopAtSafePoint(job);
          if (stop != null) {
            return stop;
          }
        }
        // Checked per row rather than per batch: a file of nothing but rejected rows never fills a
        // batch, and it would otherwise run to the end without a single heartbeat -- long enough
        // for
        // the recovery scan to declare its worker dead and requeue a job that is still running.
        if (checkpoint.isDue(counters.processedRows, Instant.now(clock))) {
          stage(job, batch);
          boolean stopRequested = persistProgress(job, counters);
          checkpoint.reset(counters.processedRows, Instant.now(clock));
          if (stopRequested) {
            // Safe point: the pending batch is committed and nothing is half-written.
            return Outcome.stoppedByCancellation();
          }
          if (workerControl.isStopping()) {
            return Outcome.stoppedByShutdown();
          }
        }
      }
      stage(job, batch);
      if (persistProgress(job, counters)) {
        // Checked before publishing rather than after: a cancelled job drops the report key, so
        // uploading first would leave an object in the bucket that nothing references.
        return Outcome.stoppedByCancellation();
      }
      if (workerControl.isStopping()) {
        return Outcome.stoppedByShutdown();
      }

      StagingResolution resolution =
          customerImportStaging.resolve(job.getId(), job.getCurrentAttempt());
      counters.validRows = resolution.validRows();
      counters.invalidRows = resolution.invalidRows();
      if (persistProgress(job, counters)) {
        return Outcome.stoppedByCancellation();
      }

      Outcome stopped = mergeCanonicalRows(job, counters, checkpoint);
      if (stopped != null) {
        return stopped;
      }
      return publishReport(job, counters, checkpoint);
    }
  }

  private void stage(ProcessingJob job, List<StagedCustomerRow> batch) {
    if (batch.isEmpty()) {
      return;
    }
    customerImportStaging.append(job.getId(), job.getCurrentAttempt(), batch);
    batch.clear();
  }

  private Outcome mergeCanonicalRows(ProcessingJob job, Counters counters, Checkpoint checkpoint) {
    long afterRowNumber = 0;
    while (true) {
      List<StagedCustomerRow> canonical =
          customerImportStaging.canonicalRowsAfter(
              job.getId(), job.getCurrentAttempt(), afterRowNumber, properties.batchSize());
      if (canonical.isEmpty()) {
        return null;
      }
      List<ImportCustomerRow> customers =
          canonical.stream().map(ProcessingJobRunner::toImportRow).toList();
      ImportCustomerBatchResult result =
          customerImportService.importBatch(new ImportCustomerBatchCommand(job.getId(), customers));
      counters.insertedRows += result.insertedRows();
      counters.updatedRows += result.updatedRows();
      afterRowNumber = canonical.getLast().originalRow().rowNumber();

      Outcome stopped = stopAtSafePoint(job);
      if (stopped != null) {
        return stopped;
      }
      if (checkpoint.isDue(counters.processedRows, Instant.now(clock))) {
        if (persistProgress(job, counters)) {
          return Outcome.stoppedByCancellation();
        }
        checkpoint.reset(counters.processedRows, Instant.now(clock));
      }
    }
  }

  /**
   * Streams the report in source order, page by page.
   *
   * <p>Checkpointed like every other phase. A report for a file of mostly rejected rows takes long
   * enough on its own to outlast the stale-heartbeat threshold, and a worker that goes quiet here
   * is declared lost while it is still working -- which also clears the workspace it is reading
   * from.
   */
  private Outcome publishReport(ProcessingJob job, Counters counters, Checkpoint checkpoint) {
    try (ErrorReportStore.Draft report = errorReportStore.open(job.getId())) {
      long afterRowNumber = 0;
      while (true) {
        List<StagedReportRow> records =
            customerImportStaging.reportRowsAfter(
                job.getId(), job.getCurrentAttempt(), afterRowNumber, properties.batchSize());
        if (records.isEmpty()) {
          break;
        }
        for (StagedReportRow record : records) {
          report.write(record.issue(), record.originalRow());
        }
        afterRowNumber = records.getLast().issue().rowNumber();

        Outcome stopped = stopAtSafePoint(job);
        if (stopped != null) {
          return stopped;
        }
        if (checkpoint.isDue(counters.processedRows, Instant.now(clock))) {
          if (persistProgress(job, counters)) {
            return Outcome.stoppedByCancellation();
          }
          checkpoint.reset(counters.processedRows, Instant.now(clock));
        }
      }
      Outcome stopped = stopAtSafePoint(job);
      return stopped == null ? Outcome.finished(report.publish()) : stopped;
    }
  }

  /** A completed batch is the cancellation and shutdown safe point. */
  private Outcome stopAtSafePoint(ProcessingJob job) {
    if (workerControl.isStopping()) {
      return Outcome.stoppedByShutdown();
    }
    return processingJobCommandService.isCancellationRequested(job.getId())
        ? Outcome.stoppedByCancellation()
        : null;
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
      return processingJobCommandService.recordProgress(job.getId(), counters.snapshot());
    } catch (OptimisticLockingFailureException conflict) {
      log.debug("[run] jobId={} checkpoint skipped, job changed concurrently", job.getId());
      // The write that won was most likely the cancellation request itself.
      return processingJobCommandService.isCancellationRequested(job.getId());
    }
  }

  private void failQuietly(ProcessingJob job, String code, String summary, Exception cause) {
    // The message is sanitized on purpose: a raw driver or SDK error can carry connection strings,
    // credentials or row content, and it ends up in an API response.
    // Parser, JDBC and object-storage exception messages can include a customer value, a bucket
    // path or credentials. Keep the diagnostic category without serialising those details to logs.
    log.error(
        "[run] jobId={} failed code={} causeType={} rule={}",
        job.getId(),
        code,
        cause.getClass().getSimpleName(),
        cause instanceof ProcessingJobRuleViolation violation ? violation.getRule() : "n/a");
    try {
      processingJobCommandService.fail(job.getId(), code, summary);
    } catch (RuntimeException failure) {
      log.error(
          "[run] jobId={} could not be marked failed causeType={}",
          job.getId(),
          failure.getClass().getSimpleName());
    }
  }

  /**
   * Captures already committed customer merge batches before a later batch fails.
   *
   * <p>Normal progress remains rate-limited. This is the exceptional path, where preserving exact
   * terminal counters matters more than avoiding one final short transaction.
   */
  private void checkpointBeforeFailure(ProcessingJob job, Counters counters) {
    try {
      processingJobCommandService.recordProgress(job.getId(), counters.snapshot());
    } catch (RuntimeException exception) {
      log.warn(
          "[run] could not checkpoint before failure jobId={} causeType={}",
          job.getId(),
          exception.getClass().getSimpleName());
    }
  }

  private static ImportCustomerRow toImportRow(StagedCustomerRow staged) {
    NormalizedCustomerRow row = staged.normalizedRow().orElseThrow();
    return new ImportCustomerRow(
        row.externalId(),
        row.fullName(),
        row.email(),
        row.phone(),
        row.dateOfBirth(),
        row.address());
  }

  private void clearStaging(ProcessingJob job) {
    try {
      customerImportStaging.clear(job.getId(), job.getCurrentAttempt());
    } catch (RuntimeException exception) {
      // The stage is a reconstructable workspace. Do not hide the actual processing failure or
      // turn a successful job into a failed one merely because best-effort PII cleanup raced a DB
      // outage; the cleanup task can safely retry this idempotent deletion.
      log.warn(
          "[run] could not clear staging jobId={} attempt={} causeType={}",
          job.getId(),
          job.getCurrentAttempt(),
          exception.getClass().getSimpleName());
    }
  }

  private record Outcome(boolean cancelled, boolean shutdown, String reportKey) {

    static Outcome stoppedByCancellation() {
      return new Outcome(true, false, null);
    }

    static Outcome stoppedByShutdown() {
      return new Outcome(false, true, null);
    }

    static Outcome finished(String reportKey) {
      return new Outcome(false, false, reportKey);
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

  /**
   * The totals as the run accumulates them, incremented row by row. {@link #snapshot()} is the one
   * place they become the immutable value the job is told about, so no boundary unpacks them into
   * loose arguments again.
   */
  private static final class Counters {

    private long processedRows;
    private long validRows;
    private long invalidRows;
    private long insertedRows;
    private long updatedRows;

    private RowCounters snapshot() {
      return RowCounters.builder()
          .processedRows(processedRows)
          .validRows(validRows)
          .invalidRows(invalidRows)
          .insertedRows(insertedRows)
          .updatedRows(updatedRows)
          .build();
    }
  }
}
