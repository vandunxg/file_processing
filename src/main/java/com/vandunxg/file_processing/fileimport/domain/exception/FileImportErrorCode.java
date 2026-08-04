package com.vandunxg.file_processing.fileimport.domain.exception;

import com.vandunxg.common.models.error.ResponseError;

public enum FileImportErrorCode implements ResponseError {
  FILE_REQUIRED(50001, "A file is required", 400),
  ONLY_ONE_FILE_ALLOWED(50002, "Exactly one file is allowed", 400),
  EMPTY_FILE(50003, "File must not be empty", 400),
  STORAGE_UNAVAILABLE(50004, "File storage is unavailable", 503),
  DUPLICATE_FILE(50005, "An identical file has already been uploaded", 409),
  UNSUPPORTED_FILE_TYPE(50006, "Only CSV files are supported", 415),
  FILE_IMPORT_NOT_FOUND(50007, "File import was not found", 404),
  REPORT_NOT_AVAILABLE(50008, "A final error report is not available", 409),
  INVALID_CSV_HEADER(50009, "The CSV header is invalid", 422);

  private final Integer code;
  private final String message;
  private final int status;

  FileImportErrorCode(Integer code, String message, int status) {
    this.code = code;
    this.message = message;
    this.status = status;
  }

  @Override
  public Integer getCode() {
    return code;
  }

  @Override
  public String getName() {
    return name();
  }

  @Override
  public String getMessage() {
    return message;
  }

  @Override
  public int getStatus() {
    return status;
  }
}
