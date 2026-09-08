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
 */
public interface CustomerImportStaging {

  void append(UUID jobId, int attemptNumber, List<StagedCustomerRow> rows);

  /** Assigns a rank to every field-valid row and returns the final validation counters. */
  StagingResolution resolve(UUID jobId, int attemptNumber);

  /** Returns canonical rows after the supplied physical line number, in source order. */
  List<StagedCustomerRow> canonicalRowsAfter(
      UUID jobId, int attemptNumber, long afterRowNumber, int limit);

  /** Returns report records after the supplied stable ordering key. */
  List<StagedReportRow> reportRowsAfter(
      UUID jobId,
      int attemptNumber,
      long afterRowNumber,
      int afterIssueOrder,
      int afterSource,
      int limit);

  /** Removes PII held only for the active attempt. This operation is idempotent. */
  void clear(UUID jobId, int attemptNumber);
}
