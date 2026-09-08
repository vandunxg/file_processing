package com.vandunxg.file_processing.customer.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.vandunxg.file_processing.customer.application.result.ImportCustomerBatchResult;
import com.vandunxg.file_processing.customer.domain.model.Customer;
import com.vandunxg.file_processing.testsupport.PostgresTestContainerBase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class PostgresCustomerBatchWriterIT extends PostgresTestContainerBase {

  private static final LocalDate DATE_OF_BIRTH = LocalDate.parse("2000-01-02");

  private static JdbcTemplate jdbc;

  private PostgresCustomerBatchWriter writer;
  private UUID firstJobId;
  private UUID secondJobId;

  @BeforeAll
  static void migrate() {
    var dataSource =
        new DriverManagerDataSource(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    Flyway.configure().dataSource(dataSource).load().migrate();
    jdbc = new JdbcTemplate(dataSource);
  }

  @BeforeEach
  void setUp() {
    jdbc.update("DELETE FROM customers");
    // Attempts reference their job, so they have to go first.
    jdbc.update("DELETE FROM processing_attempt");
    jdbc.update("DELETE FROM processing_job");
    jdbc.update("DELETE FROM file_import");
    firstJobId = insertJob();
    secondJobId = insertJob();
    writer = new PostgresCustomerBatchWriter(jdbc);
  }

  @Test
  void upsertAllInsertsEveryCustomerWhoseExternalIdIsNew() {
    ImportCustomerBatchResult result =
        writer.upsertAll(
            List.of(
                customer("CUS_01", "Nguyen Van A", "a@example.com", "1 Main St", firstJobId),
                customer("CUS_02", "Tran Van B", "b@example.com", null, firstJobId)));

    assertThat(result).isEqualTo(new ImportCustomerBatchResult(2, 0));
    assertThat(jdbc.queryForObject("SELECT count(*) FROM customers", Long.class)).isEqualTo(2L);
  }

  @Test
  void upsertAllOverwritesTheImportedFieldsAndKeepsTheInternalIdentityOfAnExistingCustomer() {
    writer.upsertAll(
        List.of(customer("CUS_01", "Nguyen Van A", "a@example.com", "1 Main St", firstJobId)));
    UUID originalId = (UUID) storedCustomer().get("id");

    ImportCustomerBatchResult result =
        writer.upsertAll(
            List.of(customer("CUS_01", "Tran Van B", "b@example.com", "2 Main St", secondJobId)));

    assertThat(result).isEqualTo(new ImportCustomerBatchResult(0, 1));
    assertThat(storedCustomer())
        .containsEntry("id", originalId)
        .containsEntry("full_name", "Tran Van B")
        .containsEntry("email", "b@example.com")
        .containsEntry("address", "2 Main St")
        .containsEntry("last_import_job_id", secondJobId);
  }

  @Test
  void upsertAllOverwritesAStoredAddressWithNullWhenTheImportedAddressIsAbsent() {
    writer.upsertAll(
        List.of(customer("CUS_01", "Nguyen Van A", "a@example.com", "1 Main St", firstJobId)));

    writer.upsertAll(
        List.of(customer("CUS_01", "Nguyen Van A", "a@example.com", null, secondJobId)));

    assertThat(storedCustomer()).containsEntry("address", null);
  }

  @Test
  void upsertAllCountsAnExistingCustomerAsUpdatedEvenWhenEveryValueIsIdentical() {
    writer.upsertAll(
        List.of(customer("CUS_01", "Nguyen Van A", "a@example.com", "1 Main St", firstJobId)));

    ImportCustomerBatchResult result =
        writer.upsertAll(
            List.of(customer("CUS_01", "Nguyen Van A", "a@example.com", "1 Main St", firstJobId)));

    assertThat(result).isEqualTo(new ImportCustomerBatchResult(0, 1));
  }

  @Test
  void upsertAllKeepsCustomersThatALaterImportOmits() {
    writer.upsertAll(
        List.of(
            customer("CUS_01", "Nguyen Van A", "a@example.com", null, firstJobId),
            customer("CUS_02", "Tran Van B", "b@example.com", null, firstJobId)));

    writer.upsertAll(
        List.of(customer("CUS_01", "Nguyen Van A", "a@example.com", null, secondJobId)));

    assertThat(
            jdbc.queryForObject(
                "SELECT count(*) FROM customers WHERE external_id = 'CUS_02'", Long.class))
        .isOne();
  }

  @Test
  void upsertAllAppliesTheWholeBatchOrNoneOfItWhenTheDatabaseRejectsOneRow() {
    assertThatThrownBy(
            () ->
                writer.upsertAll(
                    List.of(
                        customer("CUS_01", "Nguyen Van A", "a@example.com", null, firstJobId),
                        customer("CUS_02", "x".repeat(200), "b@example.com", null, firstJobId))))
        .isInstanceOf(RuntimeException.class);

    assertThat(jdbc.queryForObject("SELECT count(*) FROM customers", Long.class)).isZero();
  }

  private Map<String, Object> storedCustomer() {
    return jdbc.queryForMap("SELECT * FROM customers WHERE external_id = 'CUS_01'");
  }

  private static Customer customer(
      String externalId, String fullName, String email, String address, UUID jobId) {
    return Customer.importedFrom(
        externalId, fullName, email, "+84912345678", DATE_OF_BIRTH, address, jobId);
  }

  private UUID insertJob() {
    UUID fileId = UUID.randomUUID();
    UUID jobId = UUID.randomUUID();
    Instant now = Instant.now();
    jdbc.update(
        """
        INSERT INTO file_import (
          id, owner_id, original_filename, storage_key, checksum_sha256, size_bytes,
          detected_content_type, retention_deadline, bucket, storage_provider,
          created_at, last_modified_at
        ) VALUES (?, ?, 'customers.csv', ?, ?, 1, 'text/csv', ?, 'file-processing', 'R2', ?, ?)
        """,
        fileId,
        UUID.randomUUID(),
        "imports/" + fileId + ".csv",
        randomChecksum(),
        java.sql.Timestamp.from(now.plusSeconds(3600)),
        java.sql.Timestamp.from(now),
        java.sql.Timestamp.from(now));
    jdbc.update(
        """
        INSERT INTO processing_job (id, import_file_id, owner_id, status, created_at, last_modified_at)
        VALUES (?, ?, ?, 'QUEUED', ?, ?)
        """,
        jobId,
        fileId,
        UUID.randomUUID(),
        java.sql.Timestamp.from(now),
        java.sql.Timestamp.from(now));
    return jobId;
  }

  private static String randomChecksum() {
    return UUID.randomUUID().toString().replace("-", "").repeat(2);
  }
}
