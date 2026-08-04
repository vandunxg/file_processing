package com.vandunxg.file_processing.fileimport.application.service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vandunxg.file_processing.fileimport.application.validation.ParsedCustomerRow;
import com.vandunxg.file_processing.fileimport.application.validation.ValidationIssue;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;

final class CsvErrorReportWriter implements AutoCloseable {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final CSVPrinter printer;

  CsvErrorReportWriter(Path path) throws IOException {
    var writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8);
    writer.write('\uFEFF');
    printer =
        new CSVPrinter(
            writer,
            CSVFormat.DEFAULT
                .builder()
                .setHeader(
                    "row_number",
                    "external_id",
                    "error_code",
                    "field",
                    "error_message",
                    "original_data")
                .get());
  }

  void write(ValidationIssue issue, ParsedCustomerRow row) {
    try {
      printer.printRecord(
          issue.rowNumber(),
          issue.externalId(),
          issue.code(),
          issue.field(),
          issue.message(),
          JSON.writeValueAsString(originalData(row)));
    } catch (IOException exception) {
      throw new UncheckedIOException("Unable to write CSV error report", exception);
    }
  }

  @Override
  public void close() throws IOException {
    printer.close();
  }

  private static Map<String, String> originalData(ParsedCustomerRow row) {
    Map<String, String> data = new LinkedHashMap<>();
    data.put("external_id", row.externalId());
    data.put("full_name", row.fullName());
    data.put("email", row.email());
    data.put("phone", row.phone());
    data.put("date_of_birth", row.dateOfBirth());
    data.put("address", row.address());
    return data;
  }
}
