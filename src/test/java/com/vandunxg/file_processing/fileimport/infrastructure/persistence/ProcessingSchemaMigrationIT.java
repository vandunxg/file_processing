package com.vandunxg.file_processing.fileimport.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.vandunxg.file_processing.testsupport.AuthIntegrationTestBase;
import com.vandunxg.file_processing.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

@PostgresIntegrationTest
class ProcessingSchemaMigrationIT extends AuthIntegrationTestBase {

  @Autowired private JdbcTemplate jdbcTemplate;

  @Test
  void expandsTheSchemaForJobsAttemptsAndCustomerJobProvenance() {
    assertThat(regclass("processing_job")).isEqualTo("processing_job");
    assertThat(regclass("processing_attempt")).isEqualTo("processing_attempt");
    assertThat(
            jdbcTemplate.queryForObject(
                """
                select count(*)
                from information_schema.columns
                where table_schema = 'public'
                  and table_name = 'customers'
                  and column_name = 'last_import_job_id'
                """,
                Long.class))
        .isOne();
  }

  private String regclass(String tableName) {
    return jdbcTemplate.queryForObject(
        "select to_regclass(?)", String.class, "public." + tableName);
  }
}
