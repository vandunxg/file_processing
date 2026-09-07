package com.vandunxg.file_processing.auth.application.exception;

import com.vandunxg.common.models.error.ResponseError;
import com.vandunxg.common.models.exception.ResponseException;
import com.vandunxg.file_processing.auth.domain.exception.AuthRuleViolation;

public class AuthException extends ResponseException {

  public AuthException(ResponseError error) {
    super(error);
  }

  public AuthException(ResponseError error, Object... params) {
    super(error, params);
  }

  public AuthException(String message, Throwable cause, ResponseError error, Object... params) {
    super(message, cause, error, params);
  }

  /** Translates an aggregate rule violation into this module's error contract. */
  public static AuthException of(AuthRuleViolation violation) {
    return new AuthException(
        violation.getMessage(), violation, AuthErrorCode.from(violation.getRule()));
  }
}
