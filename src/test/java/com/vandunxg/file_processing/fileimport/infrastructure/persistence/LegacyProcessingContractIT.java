package com.vandunxg.file_processing.fileimport.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import com.vandunxg.file_processing.testsupport.PostgresTestContainerBase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * The contract step drops the legacy processing columns, so it must refuse to run while anything
 * still depends on them.
 *
 * <p>Each test migrates its own database: the shared one is already at head, where the columns are
 * gone and there is nothing left to verify.
 */
class LegacyProcessingContractIT extends PostgresTestContainerBase {

  private static final String VERSION_BEFORE_CONTRACT = "202609081300";

  @Test
  void dropsTheLegacyProcessingColumnsOnceEveryFileHasAJob() {
    JdbcTemplate jdbc = migratedToHead();

    assertThat(columnCount(jdbc, "file_import", "processing_status")).isZero();
    assertThat(columnCount(jdbc, "file_import", "processed_rows")).isZero();
    assertThat(columnCount(jdbc, "file_import", "valid_rows")).isZero();
    assertThat(columnCount(jdbc, "file_import", "invalid_rows")).isZero();
    assertThat(columnCount(jdbc, "file_import", "inserted_rows")).isZero();
    assertThat(columnCount(jdbc, "file_import", "updated_rows")).isZero();
    assertThat(columnCount(jdbc, "file_import", "error_report_key")).isZero();
    assertThat(columnCount(jdbc, "customers", "last_import_file_id")).isZero();
    // The replacement stays.
    assertThat(columnCount(jdbc, "customers", "last_import_job_id")).isOne();
  }

  @Test
  void refusesToDropTheColumnsWhileAFileStillHasNoJob() {
    DriverManagerDataSource target = freshDatabase();
    JdbcTemplate jdbc = new JdbcTemplate(target);
    Flyway.configure().dataSource(target).target(VERSION_BEFORE_CONTRACT).load().migrate();
    insertLegacyFileWithoutAJob(jdbc);

    assertThatThrownBy(() -> Flyway.configure().dataSource(target).load().migrate())
        .hasMessageContaining("processing_job");
    // Nothing was dropped, so the data is still reachable and the backfill can be re-run.
    assertThat(columnCount(jdbc, "file_import", "processing_status")).isOne();
  }

  @Test
  void refusesToDropTheColumnsWhileACustomersProvenanceHasNotMoved() {
    DriverManagerDataSource target = freshDatabase();
    JdbcTemplate jdbc = new JdbcTemplate(target);
    Flyway.configure().dataSource(target).target(VERSION_BEFORE_CONTRACT).load().migrate();
    insertCustomerPointingAtAFileOnly(jdbc);

    assertThatThrownBy(() -> Flyway.configure().dataSource(target).load().migrate())
        .hasMessageContaining("last_import_job_id");
    assertThat(columnCount(jdbc, "customers", "last_import_file_id")).isOne();
  }

  private JdbcTemplate migratedToHead() {
    DriverManagerDataSource target = freshDatabase();
    Flyway.configure().dataSource(target).load().migrate();
    return new JdbcTemplate(target);
  }

  private static Long columnCount(JdbcTemplate jdbc, String table, String column) {
    return jdbc.queryForObject(
        """
        SELECT count(*) FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = ? AND column_name = ?
        """,
        Long.class,
        table,
        column);
  }

  private static void insertLegacyFileWithoutAJob(JdbcTemplate jdbc) {
    UUID fileId = UUID.randomUUID();
    Instant now = Instant.parse("2026-08-01T00:00:00Z");
    jdbc.update(
        """
        INSERT INTO file_import (
          id, owner_id, original_filename, storage_key, checksum_sha256, size_bytes,
          detected_content_type, retention_deadline, bucket, storage_provider,
          processing_status, created_at, last_modified_at
        ) VALUES (?, ?, 'customers.csv', ?, ?, 1, 'text/csv', ?, 'file-processing', 'R2',
                  'COMPLETED', ?, ?)
        """,
        fileId,
        UUID.randomUUID(),
        "imports/" + fileId + ".csv",
        UUID.randomUUID().toString().replace("-", "").repeat(2),
        Timestamp.from(now.plusSeconds(86_400)),
        Timestamp.from(now),
        Timestamp.from(now));
  }

  private static void insertCustomerPointingAtAFileOnly(JdbcTemplate jdbc) {
    jdbc.update(
        """
        INSERT INTO customers (
          id, external_id, full_name, email, phone, date_of_birth, address, last_import_file_id
        ) VALUES (?, ?, 'Nguyen Van A', 'a@example.com', '+84912345678', DATE '2000-01-02', NULL, ?)
        """,
        UUID.randomUUID(),
        "CUS_" + System.nanoTime(),
        UUID.randomUUID());
  }

  private DriverManagerDataSource freshDatabase() {
    String database = "contract_" + UUID.randomUUID().toString().replace("-", "");
    new JdbcTemplate(dataSourceFor(POSTGRES.getDatabaseName()))
        .execute("CREATE DATABASE " + database);
    return dataSourceFor(database);
  }

  private static DriverManagerDataSource dataSourceFor(String database) {
    String url =
        POSTGRES
            .getJdbcUrl()
            .replaceFirst("/" + POSTGRES.getDatabaseName() + "(\\?|$)", "/" + database + "$1");
    return new DriverManagerDataSource(url, POSTGRES.getUsername(), POSTGRES.getPassword());
  }
}
