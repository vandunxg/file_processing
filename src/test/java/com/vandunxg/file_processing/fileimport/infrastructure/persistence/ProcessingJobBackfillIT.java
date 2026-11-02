package com.vandunxg.file_processing.fileimport.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.vandunxg.file_processing.testsupport.PostgresTestContainerBase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * Proves the backfill actually converts legacy state, by migrating to the version just before it,
 * writing rows that look like production, and only then applying it.
 *
 * <p>Runs against its own database: the shared one is migrated to head before any test starts, so
 * there would be nothing left to backfill and the migration would be asserted against an empty
 * table.
 */
class ProcessingJobBackfillIT extends PostgresTestContainerBase {

  private static final String VERSION_BEFORE_BACKFILL = "202609081230";

  private static JdbcTemplate jdbc;

  private static final UUID COMPLETED_FILE = UUID.randomUUID();
  private static final UUID WITH_ERRORS_FILE = UUID.randomUUID();
  private static final UUID INTERRUPTED_FILE = UUID.randomUUID();
  private static final UUID CUSTOMER_ID = UUID.randomUUID();

  @BeforeAll
  static void migrateWithLegacyDataInPlace() {
    String database = "backfill_" + UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(dataSourceFor(POSTGRES.getDatabaseName()))
        .execute("CREATE DATABASE " + database);
    DriverManagerDataSource target = dataSourceFor(database);
    jdbc = new JdbcTemplate(target);

    Flyway.configure().dataSource(target).target(VERSION_BEFORE_BACKFILL).load().migrate();
    insertLegacyFile(COMPLETED_FILE, "COMPLETED", 10, 10, 0, 7, 3, null);
    insertLegacyFile(
        WITH_ERRORS_FILE, "COMPLETED_WITH_ERRORS", 10, 8, 2, 5, 3, "reports/legacy.csv");
    insertLegacyFile(INTERRUPTED_FILE, "PROCESSING", 4, 4, 0, 4, 0, null);
    insertLegacyCustomer();

    Flyway.configure().dataSource(target).load().migrate();
  }

  @Test
  void everyLegacyFileGetsExactlyOneCanonicalJob() {
    assertThat(jdbc.queryForObject("SELECT count(*) FROM processing_job", Long.class))
        .isEqualTo(3L);
    assertThat(
            jdbc.queryForObject(
                "SELECT count(DISTINCT import_file_id) FROM processing_job", Long.class))
        .isEqualTo(3L);
  }

  @Test
  void aFinishedLegacyImportKeepsItsCountersAndItsFinalStatus() {
    Map<String, Object> job = jobOf(WITH_ERRORS_FILE);

    assertThat(job)
        .containsEntry("status", "COMPLETED_WITH_ERRORS")
        .containsEntry("processed_rows", 10L)
        .containsEntry("valid_rows", 8L)
        .containsEntry("invalid_rows", 2L)
        .containsEntry("inserted_rows", 5L)
        .containsEntry("updated_rows", 3L)
        .containsEntry("total_rows", 10L)
        .containsEntry("progress_percent", 100)
        .containsEntry("error_report_key", "reports/legacy.csv");
  }

  @Test
  void aSuccessfulLegacyImportCarriesNoReportKey() {
    assertThat(jobOf(COMPLETED_FILE))
        .containsEntry("status", "COMPLETED")
        .containsEntry("error_report_key", null);
  }

  @Test
  void aLegacyImportStillMarkedRunningBecomesFailedRatherThanRunning() {
    // No worker owns that state after cutover, so leaving it PROCESSING would strand the job in a
    // state nothing can finish. FAILED is honest and retryable.
    assertThat(jobOf(INTERRUPTED_FILE))
        .containsEntry("status", "FAILED")
        .containsEntry("error_code", "LEGACY_IMPORT_INCOMPLETE");
    assertThat(jobOf(INTERRUPTED_FILE).get("total_rows")).isNull();
  }

  @Test
  void everyBackfilledJobHasOneAttemptSoItsHistoryIsNotEmpty() {
    assertThat(jdbc.queryForObject("SELECT count(*) FROM processing_attempt", Long.class))
        .isEqualTo(3L);
    assertThat(
            jdbc.queryForObject(
                """
                SELECT attempt.status FROM processing_attempt attempt
                JOIN processing_job job ON job.id = attempt.job_id
                WHERE job.import_file_id = ?
                """,
                String.class,
                INTERRUPTED_FILE))
        .isEqualTo("FAILED");
  }

  @Test
  void customerProvenanceMovesFromTheFileToTheJobResponsibleForIt() {
    UUID expectedJobId = (UUID) jobOf(COMPLETED_FILE).get("id");

    assertThat(
            jdbc.queryForObject(
                "SELECT last_import_job_id FROM customers WHERE id = ?", UUID.class, CUSTOMER_ID))
        .isEqualTo(expectedJobId);
  }

  private static Map<String, Object> jobOf(UUID fileId) {
    return jdbc.queryForMap("SELECT * FROM processing_job WHERE import_file_id = ?", fileId);
  }

  private static DriverManagerDataSource dataSourceFor(String database) {
    String url =
        POSTGRES
            .getJdbcUrl()
            .replaceFirst("/" + POSTGRES.getDatabaseName() + "(\\?|$)", "/" + database + "$1");
    return new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static void insertLegacyFile(
      UUID fileId,
      String processingStatus,
      long processedRows,
      long validRows,
      long invalidRows,
      long insertedRows,
      long updatedRows,
      String errorReportKey) {
    Instant now = Instant.parse("2026-08-01T00:00:00Z");
    jdbc.update(
        """
        INSERT INTO file_import (
          id, owner_id, original_filename, storage_key, checksum_sha256, size_bytes,
          detected_content_type, retention_deadline, bucket, storage_provider,
          processing_status, processed_rows, valid_rows, invalid_rows, inserted_rows, updated_rows,
          error_report_key, created_at, last_modified_at
        ) VALUES (?, ?, 'customers.csv', ?, ?, 1, 'text/csv', ?, 'file-processing', 'R2',
                  ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """,
        fileId,
        UUID.randomUUID(),
        "imports/" + fileId + ".csv",
        UUID.randomUUID().toString().replace("-", "").repeat(2),
        Timestamp.from(now.plusSeconds(86_400)),
        processingStatus,
        processedRows,
        validRows,
        invalidRows,
        insertedRows,
        updatedRows,
        errorReportKey,
        Timestamp.from(now),
        Timestamp.from(now.plusSeconds(60)));
  }

  private static void insertLegacyCustomer() {
    jdbc.update(
        """
        INSERT INTO customers (
          id, external_id, full_name, email, phone, date_of_birth, address, last_import_file_id
        ) VALUES (?, 'CUS_LEGACY', 'Nguyen Van A', 'a@example.com', '+84912345678',
                  DATE '2000-01-02', NULL, ?)
        """,
        CUSTOMER_ID,
        COMPLETED_FILE);
  }
}
