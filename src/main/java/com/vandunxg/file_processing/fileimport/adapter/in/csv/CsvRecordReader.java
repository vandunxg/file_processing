package com.vandunxg.file_processing.fileimport.adapter.in.csv;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import java.io.*;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class CsvRecordReader implements AutoCloseable {

  private static final List<String> REQUIRED_COLUMNS =
    List.of("external_id", "full_name", "email", "phone", "date_of_birth", "address");
  private static final int DEFAULT_MAXIMUM_DATA_ROWS = 1_000_000;
  private static final int DEFAULT_MAXIMUM_RECORD_CHARACTERS = 65_536;

  private final CSVParser parser;
  private final Iterator<CSVRecord> records;
  private final Map<String, Integer> columnIndexes;
  private final int maximumDataRows;
  private boolean hasDataRow;
  private int dataRowCount;

  public CsvRecordReader(InputStream input) {
    this(input, DEFAULT_MAXIMUM_DATA_ROWS, DEFAULT_MAXIMUM_RECORD_CHARACTERS);
  }

  CsvRecordReader(InputStream input, int maximumDataRows, int maximumRecordCharacters) {
    if (maximumDataRows <= 0 || maximumRecordCharacters <= 0) {
      throw new IllegalArgumentException("CSV limits must be positive");
    }
    this.maximumDataRows = maximumDataRows;
    InputStreamReader source =
      new InputStreamReader(
        input,
        StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT));
    try {
      parser =
        CSVParser.builder()
          .setReader(new BoundedCsvReader(source, maximumRecordCharacters))
          .setFormat(CSVFormat.RFC4180.builder().setIgnoreEmptyLines(true).get())
          .get();

      records = parser.iterator();
      columnIndexes = readHeader();
    } catch (CsvFormatException exception) {
      closeQuietly();
      close(source);
      throw exception;
    } catch (IOException | UncheckedIOException exception) {
      closeQuietly();
      close(source);
      throw new CsvFormatException(CsvErrorCode.MALFORMED_CSV, "CSV cannot be parsed", exception);
    }
  }

  public Optional<ParsedCsvRow> next() {
    try {
      while (true) {
        long rowNumber = parser.getCurrentLineNumber() + 1;
        if (!records.hasNext()) {
          if (!hasDataRow) {
            throw new CsvFormatException(
              CsvErrorCode.INVALID_CSV_HEADER, "CSV must contain at least one data row");
          }
          return Optional.empty();
        }
        CSVRecord record = records.next();
        if (record.size() != REQUIRED_COLUMNS.size()) {
          throw new CsvFormatException(
            CsvErrorCode.MALFORMED_CSV, "CSV row has an invalid column count");
        }
        if (++dataRowCount > maximumDataRows) {
          throw new CsvFormatException(CsvErrorCode.MAXIMUM_ROWS_EXCEEDED, "CSV has too many rows");
        }
        hasDataRow = true;
        return Optional.of(
          new ParsedCsvRow(
            rowNumber,
            value(record, "external_id"),
            value(record, "full_name"),
            value(record, "email"),
            value(record, "phone"),
            value(record, "date_of_birth"),
            value(record, "address")));
      }
    } catch (UncheckedIOException exception) {
      throw new CsvFormatException(CsvErrorCode.MALFORMED_CSV, "CSV cannot be parsed", exception);
    }
  }

  @Override
  public void close() {
    closeQuietly();
  }

  private Map<String, Integer> readHeader() {
    if (!records.hasNext()) {
      throw new CsvFormatException(CsvErrorCode.INVALID_CSV_HEADER, "CSV header is required");
    }
    CSVRecord header = records.next();
    Map<String, Integer> indexes = new LinkedHashMap<>();
    for (int index = 0; index < header.size(); index++) {
      String column = normalizeHeader(header.get(index), index == 0);
      if (indexes.putIfAbsent(column, index) != null) {
        throw new CsvFormatException(CsvErrorCode.INVALID_CSV_HEADER, "CSV header has duplicates");
      }
    }
    if (!indexes.keySet().equals(java.util.Set.copyOf(REQUIRED_COLUMNS))) {
      throw new CsvFormatException(CsvErrorCode.INVALID_CSV_HEADER, "CSV header is invalid");
    }
    return Map.copyOf(indexes);
  }

  private String value(CSVRecord record, String column) {
    return record.get(columnIndexes.get(column));
  }

  private static String normalizeHeader(String value, boolean firstColumn) {
    String normalized = firstColumn && value.startsWith("\uFEFF") ? value.substring(1) : value;
    return normalized.trim().toLowerCase(Locale.ROOT);
  }

  private void closeQuietly() {
    if (parser == null) {
      return;
    }
    try {
      parser.close();
    } catch (IOException ignored) {
      // The original parse error is more actionable than close failure.
    }
  }

  private static void close(Closeable closeable) {
    try {
      closeable.close();
    } catch (IOException ignored) {
      // The original parse error is more actionable than close failure.
    }
  }
}
