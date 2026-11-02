package com.vandunxg.file_processing.fileimport.application.capability;

import java.util.List;
import java.util.UUID;

import com.vandunxg.file_processing.fileimport.application.command.StagedCustomerRow;
import com.vandunxg.file_processing.fileimport.application.result.StagedReportRow;
import com.vandunxg.file_processing.fileimport.application.result.StagingResolution;

/**
 * Short-lived, attempt-scoped workspace for a streamed customer import.
 *
 * <p>The workspace deliberately belongs to File Import rather than Customer: it records source rows
 * and validation outcomes while the import decides which rows are eligible to cross the
 * bounded-context boundary. Customer only receives the canonical, fully validated rows.
 *
 * <p>Duplicate external IDs are decided by the reads below rather than by a ranking pass that
 * writes back to the workspace, so appending rows is the only write an import performs here.
 */
public interface CustomerImportStaging {

  void append(UUID jobId, int attemptNumber, List<StagedCustomerRow> rows);

  /** Returns the final validation counters once every row of the attempt has been appended. */
  StagingResolution resolve(UUID jobId, int attemptNumber);

  /** Returns canonical rows after the supplied physical line number, in source order. */
  List<StagedCustomerRow> canonicalRowsAfter(
      UUID jobId, int attemptNumber, long afterRowNumber, int limit);

  /**
   * Returns report records after the supplied physical line number, in source order.
   *
   * <p>The limit bounds source rows rather than records, so every issue belonging to a row arrives
   * in the same page and {@code afterRowNumber} alone is a complete cursor.
   */
  List<StagedReportRow> reportRowsAfter(
      UUID jobId, int attemptNumber, long afterRowNumber, int limit);

  /** Removes PII held only for the active attempt. This operation is idempotent. */
  void clear(UUID jobId, int attemptNumber);

  /**
   * Removes every workspace row that no running attempt owns, and returns how many were deleted.
   *
   * <p>Cleanup on the finishing and recovery paths is best-effort -- neither may turn a failed PII
   * deletion into a failed import -- so this is the backstop that guarantees the PII eventually
   * goes.
   */
  int clearAbandoned();
}
