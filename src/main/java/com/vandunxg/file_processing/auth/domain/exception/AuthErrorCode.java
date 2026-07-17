package com.vandunxg.file_processing.auth.domain.exception;

import com.vandunxg.common.models.error.ResponseError;

public enum AuthErrorCode implements ResponseError {
  PASSWORD_POLICY_VIOLATION(42202, "auth.error.password_policy_violation", 422),
  USERNAME_ALREADY_EXISTS(40902, "auth.error.username_already_exists", 409),
  EMAIL_ALREADY_EXISTS(40903, "auth.error.email_already_exists", 409),
  AUTH_RATE_LIMITED(42901, "auth.error.rate_limited", 429),
  EMAIL_VERIFICATION_TOKEN_INVALID(41002, "auth.error.email_verification_token_invalid", 410),
  USER_ALREADY_VERIFIED(40907, "auth.error.user_already_verified", 409),
  USER_NOT_FOUND(40401, "auth.error.user_not_found", 404),
  INVALID_ROLE(42203, "auth.error.invalid_role", 422);

  private final Integer code;
  private final String messageKey;
  private final int status;

  AuthErrorCode(Integer code, String messageKey, int status) {
    this.code = code;
    this.messageKey = messageKey;
    this.status = status;
  }

  @Override
  public String getName() {
    return name();
  }

  @Override
  public String getMessage() {
    return messageKey;
  }

  @Override
  public int getStatus() {
    return status;
  }

  @Override
  public Integer getCode() {
    return code;
  }
}
