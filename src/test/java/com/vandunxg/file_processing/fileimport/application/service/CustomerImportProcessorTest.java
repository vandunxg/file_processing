package com.vandunxg.file_processing.fileimport.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.vandunxg.file_processing.fileimport.adapter.out.persistence.CustomerUpsertResult;
import com.vandunxg.file_processing.fileimport.application.port.out.DuplicateExternalIdTracker;
import com.vandunxg.file_processing.fileimport.application.validation.NormalizedCustomerRow;
import com.vandunxg.file_processing.fileimport.application.validation.ValidatedCustomerRow;
import org.junit.jupiter.api.Test;

class CustomerImportProcessorTest {

  @Test
  void upsertsOnlyValidRowsAndReportsEveryInvalidIssue() {
    String csv =
        "external_id,full_name,email,phone,date_of_birth,address\n"
            + "CUS_01,Nguyen Van A,one@example.com,0912345678,2000-01-02,\n"
            + "CUS_01,Tran Van B,two@example.com,0912345679,2001-01-02,\n"
            + "CUS_02,A,invalid,123,2026-08-05,\n";
    List<List<NormalizedCustomerRow>> batches = new ArrayList<>();
    List<ValidatedCustomerRow> invalidRows = new ArrayList<>();

    var result =
        new CustomerImportProcessor(
                Clock.fixed(Instant.parse("2026-08-04T00:00:00Z"), ZoneOffset.UTC),
                new InMemoryTracker())
            .process(
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)),
                rows -> {
                  batches.add(List.copyOf(rows));
                  return new CustomerUpsertResult(rows.size(), 0);
                },
                invalidRows::add);

    assertThat(batches).singleElement().extracting(List::size).isEqualTo(1);
    assertThat(result.processedRows()).isEqualTo(3);
    assertThat(result.validRows()).isEqualTo(1);
    assertThat(result.invalidRows()).isEqualTo(2);
    assertThat(result.insertedRows()).isEqualTo(1);
    assertThat(result.updatedRows()).isZero();
    assertThat(invalidRows)
        .flatExtracting(ValidatedCustomerRow::issues)
        .extracting(issue -> issue.code().name())
        .containsExactly(
            "DUPLICATE_EXTERNAL_ID_IN_FILE",
            "FULL_NAME_TOO_SHORT",
            "INVALID_EMAIL",
            "INVALID_PHONE",
            "DATE_OF_BIRTH_IN_FUTURE");
  }

  private static final class InMemoryTracker implements DuplicateExternalIdTracker {

    @Override
    public Run open() {
      Set<String> externalIds = new HashSet<>();
      return new Run() {
        @Override
        public boolean firstOccurrence(String externalId) {
          return externalIds.add(externalId);
        }

        @Override
        public void close() {}
      };
    }
  }
}
