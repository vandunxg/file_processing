package com.vandunxg.file_processing.fileimport.infrastructure.csv;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;

import com.vandunxg.file_processing.fileimport.application.validation.ParsedCustomerRow;
import com.vandunxg.file_processing.fileimport.application.validation.ValidationErrorCode;
import com.vandunxg.file_processing.fileimport.application.validation.ValidationIssue;
import org.junit.jupiter.api.Test;

class CsvErrorReportWriterTest {

  @Test
  void writesBomHeaderAndOriginalRow() throws Exception {
    var path = Files.createTempFile("import-report-", ".csv");
    try (var writer = new CsvErrorReportWriter(path)) {
      writer.write(
          new ValidationIssue(2, "CUS_01", ValidationErrorCode.INVALID_EMAIL, "email", "Invalid"),
          new ParsedCustomerRow(
              2, "CUS_01", "Nguyen Van A", "invalid", "0912345678", "2000-01-02", ""));
    }

    String report = Files.readString(path);
    assertThat(report)
        .startsWith("\uFEFFrow_number,external_id,error_code,field,error_message,original_data")
        .contains("CUS_01,INVALID_EMAIL,email,Invalid")
        .contains("Nguyen Van A");
    Files.deleteIfExists(path);
  }

  @Test
  void neutralisesAValueASpreadsheetWouldTreatAsAFormula() throws Exception {
    // The uploader controls every cell of the report, and an administrator opens reports for files
    // other people uploaded. A cell left starting with '=' is executed by Excel when that
    // administrator opens it, so the uploader would be running formulas on someone else's machine.
    var path = Files.createTempFile("import-report-", ".csv");
    try (var writer = new CsvErrorReportWriter(path)) {
      writer.write(
          new ValidationIssue(
              2,
              "=1+1",
              ValidationErrorCode.DUPLICATE_EXTERNAL_ID_IN_FILE,
              "external_id",
              "Duplicate"),
          new ParsedCustomerRow(
              2, "=1+1", "@SUM(A1)", "a@example.com", "0912345678", "2000-01-02", "-2+3"));
    }

    String report = Files.readString(path);
    assertThat(report.lines().skip(1))
        .allSatisfy(
            line ->
                assertThat(line)
                    .as("no cell may begin with a formula trigger")
                    .doesNotContain(",=")
                    .doesNotContain(",@")
                    .doesNotContain(",-")
                    .doesNotContain(",+"));
    // The value is still readable, just inert.
    assertThat(report).contains("1+1");
    Files.deleteIfExists(path);
  }
}
