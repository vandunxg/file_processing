package com.vandunxg.file_processing.fileimport.adapter.in.csv;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class CsvRecordReaderTest {

  @Test
  void readsBomPrefixedRowsWithColumnsInAnyOrderAndPhysicalLineNumbers() {
    String csv =
        "\uFEFFemail,address,external_id,phone,date_of_birth,full_name\n"
            + "user@example.com,One Street,CUS_01,0912345678,2000-01-02,Nguyen Van A\n"
            + "second@example.com,\"Line one\nLine two\",CUS_02,+84912345679,2001-01-02,Tran Van B\n";

    try (var reader = new CsvRecordReader(stream(csv))) {
      assertThat(reader.next())
          .contains(
              new ParsedCsvRow(
                  2,
                  "CUS_01",
                  "Nguyen Van A",
                  "user@example.com",
                  "0912345678",
                  "2000-01-02",
                  "One Street"));
      assertThat(reader.next())
          .contains(
              new ParsedCsvRow(
                  3,
                  "CUS_02",
                  "Tran Van B",
                  "second@example.com",
                  "+84912345679",
                  "2001-01-02",
                  "Line one\nLine two"));
      assertThat(reader.next()).isEmpty();
    }
  }

  @Test
  void removesBomBeforeTrimmingTheFirstHeader() {
    String csv =
        "\uFEFF external_id , full_name , email , phone , date_of_birth , address \n"
            + "CUS_01,Nguyen Van A,user@example.com,0912345678,2000-01-02,One Street\n";

    try (var reader = new CsvRecordReader(stream(csv))) {
      assertThat(reader.next()).isPresent();
    }
  }

  @Test
  void rejectsUnexpectedHeaders() {
    assertThatThrownBy(
            () ->
                new CsvRecordReader(
                    stream("external_id,full_name,email,phone,date_of_birth,extra\n")))
        .isInstanceOf(CsvFormatException.class)
        .extracting(exception -> ((CsvFormatException) exception).code())
        .isEqualTo(CsvErrorCode.INVALID_CSV_HEADER);
  }

  @Test
  void rejectsAFileWithOnlyTheHeader() {
    try (var reader =
        new CsvRecordReader(stream("external_id,full_name,email,phone,date_of_birth,address\n"))) {
      assertThatThrownBy(reader::next)
          .isInstanceOf(CsvFormatException.class)
          .extracting(exception -> ((CsvFormatException) exception).code())
          .isEqualTo(CsvErrorCode.INVALID_CSV_HEADER);
    }
  }

  @Test
  void classifiesRowsWithMissingColumnsAsMalformedCsv() {
    try (var reader =
        new CsvRecordReader(
            stream("external_id,full_name,email,phone,date_of_birth,address\nCUS_01,Nguyen\n"))) {
      assertThatThrownBy(reader::next)
          .isInstanceOf(CsvFormatException.class)
          .extracting(exception -> ((CsvFormatException) exception).code())
          .isEqualTo(CsvErrorCode.MALFORMED_CSV);
    }
  }

  @Test
  void classifiesAQuotedEmptyRecordAsMalformedCsvInsteadOfABlankLine() {
    String csv =
        "external_id,full_name,email,phone,date_of_birth,address\n"
            + "CUS_01,Nguyen Van A,one@example.com,0912345678,2000-01-02,\n"
            + "\"\"\n";

    try (var reader = new CsvRecordReader(stream(csv))) {
      assertThat(reader.next()).isPresent();
      assertThatThrownBy(reader::next)
          .isInstanceOf(CsvFormatException.class)
          .extracting(exception -> ((CsvFormatException) exception).code())
          .isEqualTo(CsvErrorCode.MALFORMED_CSV);
    }
  }

  @Test
  void closesTheInputWhenHeaderValidationFails() {
    var input = new CloseTrackingInputStream("external_id,full_name\n");

    assertThatThrownBy(() -> new CsvRecordReader(input)).isInstanceOf(CsvFormatException.class);

    assertThat(input.closed).isTrue();
  }

  @Test
  void rejectsMalformedUtf8() {
    byte[] malformed = {
      'e',
      'x',
      't',
      'e',
      'r',
      'n',
      'a',
      'l',
      '_',
      'i',
      'd',
      ',',
      'f',
      'u',
      'l',
      'l',
      '_',
      'n',
      'a',
      'm',
      'e',
      ',',
      'e',
      'm',
      'a',
      'i',
      'l',
      ',',
      'p',
      'h',
      'o',
      'n',
      'e',
      ',',
      'd',
      'a',
      't',
      'e',
      '_',
      'o',
      'f',
      '_',
      'b',
      'i',
      'r',
      't',
      'h',
      ',',
      'a',
      'd',
      'd',
      'r',
      'e',
      's',
      's',
      '\n',
      (byte) 0xC3,
      (byte) 0x28
    };

    assertThatThrownBy(() -> new CsvRecordReader(new ByteArrayInputStream(malformed)))
        .isInstanceOf(CsvFormatException.class)
        .extracting(exception -> ((CsvFormatException) exception).code())
        .isEqualTo(CsvErrorCode.MALFORMED_CSV);
  }

  @Test
  void rejectsMoreThanTheConfiguredDataRowLimit() {
    String csv =
        "external_id,full_name,email,phone,date_of_birth,address\n"
            + "CUS_01,Nguyen Van A,one@example.com,0912345678,2000-01-02,\n"
            + "CUS_02,Tran Van B,two@example.com,0912345679,2001-01-02,\n";

    try (var reader = new CsvRecordReader(stream(csv), 1, 65_536)) {
      assertThat(reader.next()).isPresent();
      assertThatThrownBy(reader::next)
          .isInstanceOf(CsvFormatException.class)
          .extracting(exception -> ((CsvFormatException) exception).code())
          .isEqualTo(CsvErrorCode.MAXIMUM_ROWS_EXCEEDED);
    }
  }

  @Test
  void truncatesAnOversizedQuotedFieldAndContinuesWithTheNextRow() {
    String csv =
        "external_id,full_name,email,phone,date_of_birth,address\n"
            + "CUS_01,Nguyen Van A,one@example.com,0912345678,2000-01-02,\""
            + "x".repeat(100)
            + "\"\n"
            + "CUS_02,Tran Van B,two@example.com,0912345679,2001-01-02,Next Street\n";

    try (var reader = new CsvRecordReader(stream(csv), 1_000_000, 80)) {
      assertThat(reader.next().orElseThrow().address()).hasSize(80);
      assertThat(reader.next().orElseThrow().externalId()).isEqualTo("CUS_02");
    }
  }

  private static ByteArrayInputStream stream(String csv) {
    return new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
  }

  private static final class CloseTrackingInputStream extends ByteArrayInputStream {

    private boolean closed;

    private CloseTrackingInputStream(String csv) {
      super(csv.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public void close() throws IOException {
      closed = true;
      super.close();
    }
  }
}
