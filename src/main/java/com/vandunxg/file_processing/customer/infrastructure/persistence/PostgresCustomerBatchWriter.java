package com.vandunxg.file_processing.customer.infrastructure.persistence;

import java.sql.Array;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;

import com.vandunxg.file_processing.customer.application.capability.CustomerBatchWriter;
import com.vandunxg.file_processing.customer.application.result.ImportCustomerBatchResult;
import com.vandunxg.file_processing.customer.domain.model.Customer;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Applies a customer batch with one PostgreSQL upsert statement.
 *
 * <p>The batch is passed as parallel arrays rather than expanded into one {@code VALUES} tuple per
 * row, so the statement takes a fixed number of bind parameters and stays well inside the protocol
 * limit however large the configured batch is.
 *
 * <p>{@code xmax = 0} distinguishes a tuple this statement inserted from one it updated, which is
 * what lets a single round trip report both counters. An existing customer is reported as updated
 * even when every imported value is unchanged, because {@code DO UPDATE} always writes a new tuple.
 */
@Repository
@RequiredArgsConstructor
public class PostgresCustomerBatchWriter implements CustomerBatchWriter {

  private static final String UPSERT =
      """
      INSERT INTO customers (
        id, external_id, full_name, email, phone, date_of_birth, address, last_import_job_id
      )
      SELECT * FROM unnest(
        ?::uuid[], ?::varchar[], ?::varchar[], ?::varchar[],
        ?::varchar[], ?::date[], ?::varchar[], ?::uuid[]
      )
      ON CONFLICT (external_id) DO UPDATE SET
        full_name = EXCLUDED.full_name,
        email = EXCLUDED.email,
        phone = EXCLUDED.phone,
        date_of_birth = EXCLUDED.date_of_birth,
        address = EXCLUDED.address,
        last_import_job_id = EXCLUDED.last_import_job_id,
        last_modified_at = CURRENT_TIMESTAMP
      RETURNING (xmax = 0) AS inserted
      """;

  private final JdbcTemplate jdbc;

  @Override
  public ImportCustomerBatchResult upsertAll(List<Customer> customers) {
    if (customers.isEmpty()) {
      return ImportCustomerBatchResult.EMPTY;
    }
    return jdbc.execute(
        UPSERT, (PreparedStatement statement) -> bindAndCount(statement, customers));
  }

  private static ImportCustomerBatchResult bindAndCount(
      PreparedStatement statement, List<Customer> customers) throws java.sql.SQLException {
    Connection connection = statement.getConnection();
    statement.setArray(1, uuids(connection, customers, Customer::getId));
    statement.setArray(2, strings(connection, customers, Customer::getExternalId));
    statement.setArray(3, strings(connection, customers, Customer::getFullName));
    statement.setArray(4, strings(connection, customers, Customer::getEmail));
    statement.setArray(5, strings(connection, customers, Customer::getPhone));
    statement.setArray(
        6,
        connection.createArrayOf(
            "date",
            customers.stream()
                .map(customer -> Date.valueOf(customer.getDateOfBirth()))
                .toArray(Date[]::new)));
    statement.setArray(7, strings(connection, customers, Customer::getAddress));
    statement.setArray(8, uuids(connection, customers, Customer::getLastImportJobId));

    long insertedRows = 0;
    long updatedRows = 0;
    try (ResultSet rows = statement.executeQuery()) {
      while (rows.next()) {
        if (rows.getBoolean("inserted")) {
          insertedRows++;
        } else {
          updatedRows++;
        }
      }
    }
    return new ImportCustomerBatchResult(insertedRows, updatedRows);
  }

  private static Array strings(
      Connection connection,
      List<Customer> customers,
      java.util.function.Function<Customer, String> field)
      throws java.sql.SQLException {
    return connection.createArrayOf(
        "varchar", customers.stream().map(field).toArray(String[]::new));
  }

  private static Array uuids(
      Connection connection,
      List<Customer> customers,
      java.util.function.Function<Customer, UUID> field)
      throws java.sql.SQLException {
    return connection.createArrayOf("uuid", customers.stream().map(field).toArray(UUID[]::new));
  }
}
