package com.vandunxg.file_processing.fileimport.adapter.in.csv;

public class CsvFormatException extends RuntimeException {

  private final CsvErrorCode code;

  public CsvFormatException(CsvErrorCode code, String message) {
    super(message);
    this.code = code;
  }

  public CsvFormatException(CsvErrorCode code, String message, Throwable cause) {
    super(message, cause);
    this.code = code;
  }

  public CsvErrorCode code() {
    return code;
  }
}
