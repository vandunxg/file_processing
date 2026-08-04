package com.vandunxg.file_processing.fileimport.domain.exception;

import com.vandunxg.common.models.error.ResponseError;
import com.vandunxg.common.models.exception.ResponseException;

public class FileImportException extends ResponseException {

  public FileImportException(ResponseError error) {
    super(error);
  }

  public FileImportException(ResponseError error, Throwable cause) {
    super(error.getMessage(), cause, error);
  }
}
