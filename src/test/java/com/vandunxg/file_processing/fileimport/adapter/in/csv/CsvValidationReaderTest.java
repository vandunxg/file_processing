package com.vandunxg.file_processing.fileimport.adapter.in.csv;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;

import com.vandunxg.file_processing.fileimport.application.port.out.DuplicateExternalIdTracker;
import com.vandunxg.file_processing.fileimport.application.validation.ValidationErrorCode;
import org.junit.jupiter.api.Test;

class CsvValidationReaderTest {

  @Test
  void rejectsTheSecondValidOccurrenceOfAnExternalId() {
    String csv =
        "external_id,full_name,email,phone,date_of_birth,address\n"
            + "CUS_01,Nguyen Van A,one@example.com,0912345678,2000-01-02,\n"
            + "CUS_01,Tran Van B,two@example.com,0912345679,2001-01-02,\n";

    try (var reader =
        new CsvValidationReader(
            new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)),
            Clock.fixed(Instant.parse("2026-08-04T00:00:00Z"), ZoneOffset.UTC),
            new InMemoryTracker())) {
      assertThat(reader.next().orElseThrow().row()).isPresent();
      var duplicate = reader.next().orElseThrow();
      assertThat(duplicate.issues())
          .extracting(issue -> issue.code())
          .containsExactly(ValidationErrorCode.DUPLICATE_EXTERNAL_ID_IN_FILE);
      assertThat(duplicate.originalRow().fullName()).isEqualTo("Tran Van B");
    }
  }

  @Test
  void doesNotReserveAnExternalIdFromAnInvalidRow() {
    String csv =
        "external_id,full_name,email,phone,date_of_birth,address\n"
            + "CUS_01,Nguyen Van A,invalid,0912345678,2000-01-02,\n"
            + "CUS_01,Tran Van B,two@example.com,0912345679,2001-01-02,\n"
            + "CUS_01,Le Van C,three@example.com,0912345680,2002-01-02,\n";

    try (var reader =
        new CsvValidationReader(
            new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)),
            Clock.fixed(Instant.parse("2026-08-04T00:00:00Z"), ZoneOffset.UTC),
            new InMemoryTracker())) {
      assertThat(reader.next().orElseThrow().issues())
          .extracting(issue -> issue.code())
          .containsExactly(ValidationErrorCode.INVALID_EMAIL);
      assertThat(reader.next().orElseThrow().row()).isPresent();
      assertThat(reader.next().orElseThrow().issues())
          .extracting(issue -> issue.code())
          .containsExactly(ValidationErrorCode.DUPLICATE_EXTERNAL_ID_IN_FILE);
    }
  }

  @Test
  void reportsAnOversizedAddressAndContinuesStreaming() {
    String csv =
        "external_id,full_name,email,phone,date_of_birth,address\n"
            + "CUS_01,Nguyen Van A,one@example.com,0912345678,2000-01-02,\""
            + "x".repeat(70_000)
            + "\"\n"
            + "CUS_02,Tran Van B,two@example.com,0912345679,2001-01-02,Next Street\n";

    try (var reader =
        new CsvValidationReader(
            new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)),
            Clock.fixed(Instant.parse("2026-08-04T00:00:00Z"), ZoneOffset.UTC),
            new InMemoryTracker())) {
      assertThat(reader.next().orElseThrow().issues())
          .extracting(issue -> issue.code())
          .containsExactly(ValidationErrorCode.ADDRESS_TOO_LONG);
      assertThat(reader.next().orElseThrow().row()).isPresent();
    }
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
