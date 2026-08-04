package com.vandunxg.file_processing.fileimport.domain.exception;

import com.vandunxg.common.models.error.ResponseError;

public enum FileImportErrorCode implements ResponseError {
  EMPTY_FILE(50001, "File must not be empty", 400),
  STORAGE_UNAVAILABLE(50002, "File storage is unavailable", 503),
  DUPLICATE_FILE(50003, "An identical file has already been uploaded", 409);

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
