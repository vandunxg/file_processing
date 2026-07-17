package com.vandunxg.file_processing.auth.domain.exception;

import com.vandunxg.common.models.error.ResponseError;
import com.vandunxg.common.models.exception.ResponseException;

public class AuthDomainException extends ResponseException {

  public AuthDomainException(ResponseError error) {
    super(error);
  }

  public AuthDomainException(ResponseError error, Object... params) {
    super(error, params);
  }

  public AuthDomainException(String message, Throwable cause, ResponseError error, Object... params) {
    super(message, cause, error, params);
  }
}
