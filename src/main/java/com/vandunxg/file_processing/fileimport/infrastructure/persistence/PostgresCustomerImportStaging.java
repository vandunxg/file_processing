package com.vandunxg.file_processing.fileimport.infrastructure.persistence;

import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
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

  private static final String RANK_DUPLICATES =
      """
      WITH ranked AS (
        SELECT job_id,
               attempt_number,
               row_number AS source_row_number,
               ROW_NUMBER() OVER (
                 PARTITION BY normalized_external_id
                 ORDER BY row_number ASC
               )::integer AS duplicate_rank
        FROM customer_import_staging
        WHERE job_id = ? AND attempt_number = ? AND validation_passed
      )
      UPDATE customer_import_staging staging
      SET duplicate_rank = ranked.duplicate_rank
      FROM ranked
      WHERE staging.job_id = ranked.job_id
        AND staging.attempt_number = ranked.attempt_number
        AND staging.row_number = ranked.source_row_number
      """;

  private static final String RESOLUTION =
      """
      SELECT
        count(*) FILTER (WHERE validation_passed AND duplicate_rank = 1) AS valid_rows,
        count(*) FILTER (WHERE NOT validation_passed OR duplicate_rank > 1) AS invalid_rows
      FROM customer_import_staging
      WHERE job_id = ? AND attempt_number = ?
      """;

  private static final String CANONICAL_ROWS =
      """
      SELECT row_number, normalized_external_id, normalized_full_name, normalized_email,
             normalized_phone, normalized_date_of_birth, normalized_address,
             original_external_id, original_full_name, original_email, original_phone,
             original_date_of_birth, original_address
      FROM customer_import_staging
      WHERE job_id = ?
        AND attempt_number = ?
        AND duplicate_rank = 1
        AND row_number > ?
      ORDER BY row_number ASC
      LIMIT ?
      """;

  private static final String REPORT_ROWS =
      """
      WITH report_rows AS (
        SELECT issue.row_number,
               issue.external_id,
               issue.error_code,
               issue.field_name,
               issue.error_message,
               issue.issue_order,
               0 AS source,
               staging.original_external_id,
               staging.original_full_name,
               staging.original_email,
               staging.original_phone,
               staging.original_date_of_birth,
               staging.original_address
        FROM customer_import_staging_issue issue
        JOIN customer_import_staging staging
          ON staging.job_id = issue.job_id
         AND staging.attempt_number = issue.attempt_number
         AND staging.row_number = issue.row_number
        WHERE issue.job_id = ? AND issue.attempt_number = ?

        UNION ALL

        SELECT staging.row_number,
               staging.normalized_external_id,
               'DUPLICATE_EXTERNAL_ID_IN_FILE',
               'external_id',
               'External ID appears more than once in the file',
               1000,
               1,
               staging.original_external_id,
               staging.original_full_name,
               staging.original_email,
               staging.original_phone,
               staging.original_date_of_birth,
               staging.original_address
        FROM customer_import_staging staging
        WHERE staging.job_id = ?
          AND staging.attempt_number = ?
          AND staging.duplicate_rank > 1
      )
      SELECT *
      FROM report_rows
      WHERE (row_number, issue_order, source) > (?, ?, ?)
      ORDER BY row_number ASC, issue_order ASC, source ASC
      LIMIT ?
      """;

  private static final String DELETE_STAGING =
      "DELETE FROM customer_import_staging WHERE job_id = ? AND attempt_number = ?";

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
  @Transactional(timeout = 30)
  public StagingResolution resolve(UUID jobId, int attemptNumber) {
    jdbc.update(RANK_DUPLICATES, jobId, attemptNumber);
    return jdbc.queryForObject(
        RESOLUTION,
        (result, ignored) ->
            new StagingResolution(result.getLong("valid_rows"), result.getLong("invalid_rows")),
        jobId,
        attemptNumber);
  }

  @Override
  public List<StagedCustomerRow> canonicalRowsAfter(
      UUID jobId, int attemptNumber, long afterRowNumber, int limit) {
    return jdbc.query(
        CANONICAL_ROWS,
        (result, ignored) -> {
          ParsedCustomerRow original = originalRow(result);
          NormalizedCustomerRow normalized =
              new NormalizedCustomerRow(
                  result.getString("normalized_external_id"),
                  result.getString("normalized_full_name"),
                  result.getString("normalized_email"),
                  result.getString("normalized_phone"),
                  result.getObject("normalized_date_of_birth", java.time.LocalDate.class),
                  result.getString("normalized_address"));
          return new StagedCustomerRow(original, java.util.Optional.of(normalized), List.of());
        },
        jobId,
        attemptNumber,
        afterRowNumber,
        limit);
  }

  @Override
  public List<StagedReportRow> reportRowsAfter(
      UUID jobId,
      int attemptNumber,
      long afterRowNumber,
      int afterIssueOrder,
      int afterSource,
      int limit) {
    return jdbc.query(
        REPORT_ROWS,
        (result, ignored) -> {
          ValidationIssue issue =
              new ValidationIssue(
                  result.getLong("row_number"),
                  result.getString("external_id"),
                  ValidationErrorCode.valueOf(result.getString("error_code")),
                  result.getString("field_name"),
                  result.getString("error_message"));
          return new StagedReportRow(
              issue, originalRow(result), result.getInt("issue_order"), result.getInt("source"));
        },
        jobId,
        attemptNumber,
        jobId,
        attemptNumber,
        afterRowNumber,
        afterIssueOrder,
        afterSource,
        limit);
  }

  @Override
  @Transactional(timeout = 30)
  public void clear(UUID jobId, int attemptNumber) {
    jdbc.update(DELETE_STAGING, jobId, attemptNumber);
    jdbc.update(
        "DELETE FROM customer_import_staging_issue WHERE job_id = ? AND attempt_number = ?",
        jobId,
        attemptNumber);
  }

  private static ParsedCustomerRow originalRow(java.sql.ResultSet result) throws SQLException {
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
