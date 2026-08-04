package com.vandunxg.file_processing.fileimport.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.vandunxg.file_processing.fileimport.application.validation.NormalizedCustomerRow;
import com.vandunxg.file_processing.testsupport.PostgresTestContainerBase;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class CustomerUpsertRepositoryIT extends PostgresTestContainerBase {

  private static JdbcTemplate jdbc;
  private CustomerUpsertRepository repository;

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
    repository = new CustomerUpsertRepository(jdbc);
  }

  @Test
  void upsertInsertsThenUpdatesTheSameExternalId() {
    UUID firstImportId = UUID.randomUUID();
    UUID secondImportId = UUID.randomUUID();

    assertThat(
            repository.upsert(
                List.of(row("Nguyen Van A", "first@example.com", null)), firstImportId))
        .isEqualTo(new CustomerUpsertResult(1, 0));
    assertThat(
            repository.upsert(
                List.of(row("Tran Van B", "second@example.com", "2 Main St")), secondImportId))
        .isEqualTo(new CustomerUpsertResult(0, 1));

    assertThat(jdbc.queryForMap("SELECT * FROM customers WHERE external_id = 'CUS_01'"))
        .containsEntry("full_name", "Tran Van B")
        .containsEntry("email", "second@example.com")
        .containsEntry("address", "2 Main St")
        .containsEntry("last_import_file_id", secondImportId);
  }

  private static NormalizedCustomerRow row(String fullName, String email, String address) {
    return new NormalizedCustomerRow(
        "CUS_01", fullName, email, "+84912345678", LocalDate.parse("2000-01-02"), address);
  }
}
