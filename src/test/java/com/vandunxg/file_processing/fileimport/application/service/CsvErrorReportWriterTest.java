package com.vandunxg.file_processing.fileimport.application.service;

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
}
