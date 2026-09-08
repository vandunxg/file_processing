package com.vandunxg.file_processing.fileimport.infrastructure.csv;

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

/**
 * Writes the report of rejected rows.
 *
 * <p>Every cell comes from the uploaded file, and the person who opens the report is not always the
 * person who uploaded it -- an administrator reads reports for other owners' imports. A cell left
 * starting with {@code =}, {@code +}, {@code -} or {@code @} is a formula to a spreadsheet, so the
 * uploader would be choosing what runs on the reader's machine. Quoting by the CSV writer does not
 * prevent that: it protects the file's structure, not the reader's application.
 */
public final class CsvErrorReportWriter implements AutoCloseable {

  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * Characters that make a spreadsheet read a cell as a formula rather than text. The two control
   * characters are included because a leading tab or carriage return is stripped before the rest of
   * the cell is interpreted.
   */
  private static final String FORMULA_TRIGGERS = "=+-@\t\r";

  private final CSVPrinter printer;

  public CsvErrorReportWriter(Path path) throws IOException {
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

  public void write(ValidationIssue issue, ParsedCustomerRow row) {
    try {
      printer.printRecord(
          issue.rowNumber(),
          asText(issue.externalId()),
          issue.code(),
          issue.field(),
          issue.message(),
          asText(JSON.writeValueAsString(originalData(row))));
    } catch (IOException exception) {
      throw new UncheckedIOException("Unable to write CSV error report", exception);
    }
  }

  @Override
  public void close() throws IOException {
    printer.close();
  }

  /**
   * Keeps a value readable while making a spreadsheet treat it as text.
   *
   * <p>A leading apostrophe is the convention spreadsheets already understand, and they hide it
   * again when displaying the cell, so the value a reader sees is unchanged.
   */
  private static String asText(String value) {
    if (value == null || value.isEmpty() || FORMULA_TRIGGERS.indexOf(value.charAt(0)) < 0) {
      return value;
    }
    return "'" + value;
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
