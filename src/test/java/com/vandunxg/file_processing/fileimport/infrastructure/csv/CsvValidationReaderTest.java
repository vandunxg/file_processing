package com.vandunxg.file_processing.fileimport.infrastructure.csv;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import com.vandunxg.file_processing.fileimport.application.validation.ValidationErrorCode;
import org.junit.jupiter.api.Test;

class CsvValidationReaderTest {

  @Test
  void leavesDuplicateResolutionToTheAttemptStagingPhase() {
    String csv =
        "external_id,full_name,email,phone,date_of_birth,address\n"
            + "CUS_01,Nguyen Van A,one@example.com,0912345678,2000-01-02,\n"
            + "CUS_01,Tran Van B,two@example.com,0912345679,2001-01-02,\n";

    try (var reader =
        new CsvValidationReader(
            new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)),
            Clock.fixed(Instant.parse("2026-08-04T00:00:00Z"), ZoneOffset.UTC))) {
      assertThat(reader.next().orElseThrow().row()).isPresent();
      assertThat(reader.next().orElseThrow().row()).isPresent();
    }
  }

  @Test
  void keepsFieldValidationIndependentFromDuplicateResolution() {
    String csv =
        "external_id,full_name,email,phone,date_of_birth,address\n"
            + "CUS_01,Nguyen Van A,invalid,0912345678,2000-01-02,\n"
            + "CUS_01,Tran Van B,two@example.com,0912345679,2001-01-02,\n"
            + "CUS_01,Le Van C,three@example.com,0912345680,2002-01-02,\n";

    try (var reader =
        new CsvValidationReader(
            new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)),
            Clock.fixed(Instant.parse("2026-08-04T00:00:00Z"), ZoneOffset.UTC))) {
      assertThat(reader.next().orElseThrow().issues())
          .extracting(issue -> issue.code())
          .containsExactly(ValidationErrorCode.INVALID_EMAIL);
      assertThat(reader.next().orElseThrow().row()).isPresent();
      assertThat(reader.next().orElseThrow().row()).isPresent();
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
            Clock.fixed(Instant.parse("2026-08-04T00:00:00Z"), ZoneOffset.UTC))) {
      assertThat(reader.next().orElseThrow().issues())
          .extracting(issue -> issue.code())
          .containsExactly(ValidationErrorCode.ADDRESS_TOO_LONG);
      assertThat(reader.next().orElseThrow().row()).isPresent();
    }
  }
}
