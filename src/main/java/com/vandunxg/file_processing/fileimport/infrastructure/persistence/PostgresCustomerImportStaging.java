package com.vandunxg.file_processing.fileimport.infrastructure.persistence;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.vandunxg.file_processing.fileimport.application.capability.CustomerImportStaging;
import com.vandunxg.file_processing.fileimport.application.command.StagedCustomerRow;
import com.vandunxg.file_processing.fileimport.application.result.StagedReportRow;
import com.vandunxg.file_processing.fileimport.application.result.StagingResolution;
import com.vandunxg.file_processing.fileimport.application.validation.NormalizedCustomerRow;
import com.vandunxg.file_processing.fileimport.application.validation.ParsedCustomerRow;
import com.vandunxg.file_processing.fileimport.application.validation.ValidationErrorCode;
import com.vandunxg.file_processing.fileimport.application.validation.ValidationIssue;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** PostgreSQL implementation of the attempt-scoped, short-lived import workspace. */
@Repository
@RequiredArgsConstructor
public class PostgresCustomerImportStaging implements CustomerImportStaging {

  private static final String DUPLICATE_MESSAGE = "External ID appears more than once in the file";

  private static final String INSERT_ROW =
      """
      INSERT INTO customer_import_staging (
        job_id, attempt_number, row_number, validation_passed,
        normalized_external_id, normalized_full_name, normalized_email, normalized_phone,
        normalized_date_of_birth, normalized_address,
        original_external_id, original_full_name, original_email, original_phone,
        original_date_of_birth, original_address
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """;

  private static final String INSERT_ISSUE =
      """
      INSERT INTO customer_import_staging_issue (
        job_id, attempt_number, row_number, issue_order, external_id, error_code, field_name,
        error_message
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
      """;

  /**
   * A row is a rejected duplicate when an earlier field-valid row already claimed its external ID.
   * Written as a correlated EXISTS so it is one index-only probe against the dedup index, which
   * lets the same predicate serve a single counting pass and every paged read without ever writing
   * to the workspace.
   */
  private static final String EARLIER_ROW_CLAIMED_THE_ID =
      """
      EXISTS (
        SELECT 1
        FROM customer_import_staging earlier
        WHERE earlier.job_id = staging.job_id
          AND earlier.attempt_number = staging.attempt_number
          AND earlier.validation_passed
          AND earlier.normalized_external_id = staging.normalized_external_id
          AND earlier.row_number < staging.row_number
      )
      """;

  /**
   * Exactly one row per distinct external ID survives deduplication, so the canonical total is the
   * count of distinct IDs -- no per-row ranking needed to arrive at it.
   */
  private static final String RESOLUTION =
      """
      SELECT count(*) FILTER (WHERE validation_passed) AS field_valid_rows,
             count(*) FILTER (WHERE NOT validation_passed) AS field_invalid_rows,
             count(DISTINCT normalized_external_id) AS canonical_rows
      FROM customer_import_staging
      WHERE job_id = ? AND attempt_number = ?
      """;

  private static final String CANONICAL_ROWS =
      """
      SELECT staging.row_number,
             staging.normalized_external_id, staging.normalized_full_name,
             staging.normalized_email, staging.normalized_phone,
             staging.normalized_date_of_birth, staging.normalized_address,
             staging.original_external_id, staging.original_full_name, staging.original_email,
             staging.original_phone, staging.original_date_of_birth, staging.original_address
      FROM customer_import_staging staging
      WHERE staging.job_id = ?
        AND staging.attempt_number = ?
        AND staging.validation_passed
        AND staging.row_number > ?
        AND NOT %s
      ORDER BY staging.row_number ASC
      LIMIT ?
      """
          .formatted(EARLIER_ROW_CLAIMED_THE_ID);

  /**
   * Pages by source row, not by report record.
   *
   * <p>The page is chosen first, over an index-ordered scan of a bounded number of rows, and only
   * then joined to its issues. Selecting report records directly would force the whole report to be
   * assembled and re-sorted on every page, which turns publishing into a quadratic scan of the
   * workspace.
   *
   * <p>A field-valid row carries no issues and a field-invalid row is never a duplicate, so the
   * outer join separates the two cases cleanly: a null error code is the duplicate marker.
   */
  private static final String REPORT_ROWS =
      """
      WITH rejected AS (
        SELECT staging.row_number
        FROM customer_import_staging staging
        WHERE staging.job_id = ?
          AND staging.attempt_number = ?
          AND staging.row_number > ?
          AND (NOT staging.validation_passed OR %s)
        ORDER BY staging.row_number ASC
        LIMIT ?
      )
      SELECT staging.row_number,
             staging.normalized_external_id,
             staging.original_external_id, staging.original_full_name, staging.original_email,
             staging.original_phone, staging.original_date_of_birth, staging.original_address,
             issue.external_id, issue.error_code, issue.field_name, issue.error_message
      FROM rejected
      JOIN customer_import_staging staging
        ON staging.job_id = ?
       AND staging.attempt_number = ?
       AND staging.row_number = rejected.row_number
      LEFT JOIN customer_import_staging_issue issue
        ON issue.job_id = staging.job_id
       AND issue.attempt_number = staging.attempt_number
       AND issue.row_number = staging.row_number
      ORDER BY staging.row_number ASC, issue.issue_order ASC NULLS FIRST
      """
          .formatted(EARLIER_ROW_CLAIMED_THE_ID);

  private static final String DELETE_STAGING =
      "DELETE FROM customer_import_staging WHERE job_id = ? AND attempt_number = ?";

  private static final String DELETE_ISSUES =
      "DELETE FROM customer_import_staging_issue WHERE job_id = ? AND attempt_number = ?";

  /**
   * A workspace row is abandoned unless it belongs to the attempt a job is running right now.
   * Cleanup on the finishing and recovery paths is best-effort by design -- neither may turn a PII
   * deletion failure into a failed import -- so this sweep is what guarantees the PII eventually
   * goes, and it is the only thing that can reclaim an attempt whose worker never came back.
   */
  private static final String DELETE_ABANDONED =
      """
      DELETE FROM %s staging
      WHERE NOT EXISTS (
        SELECT 1
        FROM processing_job job
        WHERE job.id = staging.job_id
          AND job.current_attempt = staging.attempt_number
          AND job.status IN ('PROCESSING', 'CANCELLATION_REQUESTED')
      )
      """;

  private final JdbcTemplate jdbc;

  @Override
  @Transactional(timeout = 30)
  public void append(UUID jobId, int attemptNumber, List<StagedCustomerRow> rows) {
    if (rows.isEmpty()) {
      return;
    }
    jdbc.batchUpdate(INSERT_ROW, new RowsBatch(jobId, attemptNumber, rows));

    List<StagedIssue> issues = issuesOf(rows);
    if (!issues.isEmpty()) {
      jdbc.batchUpdate(INSERT_ISSUE, new IssuesBatch(jobId, attemptNumber, issues));
    }
  }

  @Override
  public StagingResolution resolve(UUID jobId, int attemptNumber) {
    return jdbc.queryForObject(
        RESOLUTION,
        (result, ignored) -> {
          long fieldValid = result.getLong("field_valid_rows");
          long canonical = result.getLong("canonical_rows");
          // Every field-valid row that lost its external ID to an earlier row is rejected too, so
          // the two counters still add up to every row the file produced.
          return new StagingResolution(
              canonical, result.getLong("field_invalid_rows") + (fieldValid - canonical));
        },
        jobId,
        attemptNumber);
  }

  @Override
  public List<StagedCustomerRow> canonicalRowsAfter(
      UUID jobId, int attemptNumber, long afterRowNumber, int limit) {
    return jdbc.query(
        CANONICAL_ROWS,
        (result, ignored) -> {
          NormalizedCustomerRow normalized =
              new NormalizedCustomerRow(
                  result.getString("normalized_external_id"),
                  result.getString("normalized_full_name"),
                  result.getString("normalized_email"),
                  result.getString("normalized_phone"),
                  result.getObject("normalized_date_of_birth", LocalDate.class),
                  result.getString("normalized_address"));
          return new StagedCustomerRow(originalRow(result), Optional.of(normalized), List.of());
        },
        jobId,
        attemptNumber,
        afterRowNumber,
        limit);
  }

  @Override
  public List<StagedReportRow> reportRowsAfter(
      UUID jobId, int attemptNumber, long afterRowNumber, int limit) {
    return jdbc.query(
        REPORT_ROWS,
        (result, ignored) -> new StagedReportRow(issueOf(result), originalRow(result)),
        jobId,
        attemptNumber,
        afterRowNumber,
        limit,
        jobId,
        attemptNumber);
  }

  @Override
  @Transactional(timeout = 30)
  public void clear(UUID jobId, int attemptNumber) {
    jdbc.update(DELETE_STAGING, jobId, attemptNumber);
    jdbc.update(DELETE_ISSUES, jobId, attemptNumber);
  }

  @Override
  @Transactional(timeout = 60)
  public int clearAbandoned() {
    int issues = jdbc.update(DELETE_ABANDONED.formatted("customer_import_staging_issue"));
    return issues + jdbc.update(DELETE_ABANDONED.formatted("customer_import_staging"));
  }

  private static ValidationIssue issueOf(ResultSet result) throws SQLException {
    long rowNumber = result.getLong("row_number");
    String errorCode = result.getString("error_code");
    if (errorCode == null) {
      return new ValidationIssue(
          rowNumber,
          result.getString("normalized_external_id"),
          ValidationErrorCode.DUPLICATE_EXTERNAL_ID_IN_FILE,
          "external_id",
          DUPLICATE_MESSAGE);
    }
    return new ValidationIssue(
        rowNumber,
        result.getString("external_id"),
        ValidationErrorCode.valueOf(errorCode),
        result.getString("field_name"),
        result.getString("error_message"));
  }

  private static ParsedCustomerRow originalRow(ResultSet result) throws SQLException {
    return new ParsedCustomerRow(
        result.getLong("row_number"),
        result.getString("original_external_id"),
        result.getString("original_full_name"),
        result.getString("original_email"),
        result.getString("original_phone"),
        result.getString("original_date_of_birth"),
        result.getString("original_address"));
  }

  private static List<StagedIssue> issuesOf(List<StagedCustomerRow> rows) {
    List<StagedIssue> issues = new ArrayList<>();
    for (StagedCustomerRow row : rows) {
      int issueOrder = 0;
      for (ValidationIssue issue : row.issues()) {
        issues.add(new StagedIssue(issue, issueOrder++));
      }
    }
    return issues;
  }

  private static final class RowsBatch implements BatchPreparedStatementSetter {

    private final UUID jobId;
    private final int attemptNumber;
    private final List<StagedCustomerRow> rows;

    private RowsBatch(UUID jobId, int attemptNumber, List<StagedCustomerRow> rows) {
      this.jobId = jobId;
      this.attemptNumber = attemptNumber;
      this.rows = rows;
    }

    @Override
    public void setValues(PreparedStatement statement, int index) throws SQLException {
      StagedCustomerRow staged = rows.get(index);
      ParsedCustomerRow original = staged.originalRow();
      NormalizedCustomerRow normalized = staged.normalizedRow().orElse(null);
      statement.setObject(1, jobId);
      statement.setInt(2, attemptNumber);
      statement.setLong(3, original.rowNumber());
      statement.setBoolean(4, normalized != null);
      statement.setString(5, normalized == null ? null : normalized.externalId());
      statement.setString(6, normalized == null ? null : normalized.fullName());
      statement.setString(7, normalized == null ? null : normalized.email());
      statement.setString(8, normalized == null ? null : normalized.phone());
      statement.setDate(9, normalized == null ? null : Date.valueOf(normalized.dateOfBirth()));
      statement.setString(10, normalized == null ? null : normalized.address());
      statement.setString(11, original.externalId());
      statement.setString(12, original.fullName());
      statement.setString(13, original.email());
      statement.setString(14, original.phone());
      statement.setString(15, original.dateOfBirth());
      statement.setString(16, original.address());
    }

    @Override
    public int getBatchSize() {
      return rows.size();
    }
  }

  private static final class IssuesBatch implements BatchPreparedStatementSetter {

    private final UUID jobId;
    private final int attemptNumber;
    private final List<StagedIssue> issues;

    private IssuesBatch(UUID jobId, int attemptNumber, List<StagedIssue> issues) {
      this.jobId = jobId;
      this.attemptNumber = attemptNumber;
      this.issues = issues;
    }

    @Override
    public void setValues(PreparedStatement statement, int index) throws SQLException {
      StagedIssue staged = issues.get(index);
      ValidationIssue issue = staged.issue();
      statement.setObject(1, jobId);
      statement.setInt(2, attemptNumber);
      statement.setLong(3, issue.rowNumber());
      statement.setInt(4, staged.issueOrder());
      statement.setString(5, issue.externalId());
      statement.setString(6, issue.code().name());
      statement.setString(7, issue.field());
      statement.setString(8, issue.message());
    }

    @Override
    public int getBatchSize() {
      return issues.size();
    }
  }

  private record StagedIssue(ValidationIssue issue, int issueOrder) {}
}
