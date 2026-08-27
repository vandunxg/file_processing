package com.vandunxg.file_processing.fileimport.adapter.out.persistence;

import java.util.List;
import java.util.UUID;

import com.vandunxg.file_processing.fileimport.application.validation.NormalizedCustomerRow;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class CustomerUpsertRepository {

  private static final String UPSERT =
      """
      INSERT INTO customers (
        id, external_id, full_name, email, phone, date_of_birth, address, last_import_file_id
      ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT (external_id) DO UPDATE SET
        full_name = EXCLUDED.full_name,
        email = EXCLUDED.email,
        phone = EXCLUDED.phone,
        date_of_birth = EXCLUDED.date_of_birth,
        address = EXCLUDED.address,
        last_import_file_id = EXCLUDED.last_import_file_id,
        last_modified_at = CURRENT_TIMESTAMP
      RETURNING xmax = 0
      """;

  private final JdbcTemplate jdbc;

  @Transactional
  public CustomerUpsertResult upsert(List<NormalizedCustomerRow> rows, UUID importFileId) {
    long insertedRows = 0;
    for (NormalizedCustomerRow row : rows) {
      boolean inserted =
          Boolean.TRUE.equals(
              jdbc.queryForObject(
                  UPSERT,
                  Boolean.class,
                  UUID.randomUUID(),
                  row.externalId(),
                  row.fullName(),
                  row.email(),
                  row.phone(),
                  row.dateOfBirth(),
                  row.address(),
                  importFileId));
      if (inserted) {
        insertedRows++;
      }
    }
    return new CustomerUpsertResult(insertedRows, rows.size() - insertedRows);
  }
}
