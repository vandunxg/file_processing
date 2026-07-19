package com.vandunxg.file_processing.auth.domain.exception;

import com.vandunxg.common.models.error.ResponseError;

public enum AuthErrorCode implements ResponseError {
  PASSWORD_POLICY_VIOLATION(40001, "Password does not meet policy requirements", 400),
  USERNAME_ALREADY_EXISTS(40902, "Username already exists", 409),
  EMAIL_ALREADY_EXISTS(40903, "Email already exists", 409),
  AUTH_RATE_LIMITED(42901, "Too many requests. Please try again later", 429),
  EMAIL_VERIFICATION_TOKEN_INVALID(
      40002, "Email verification token is invalid, expired, or already used", 400),
  USER_ALREADY_VERIFIED(40907, "Email has already been verified", 409),
  USER_NOT_FOUND(40401, "User not found", 404),
  INVALID_CREDENTIALS(40101, "Invalid username or password", 401),
  ACCOUNT_LOCKED(40301, "Account is locked", 403),
  REFRESH_TOKEN_INVALID(40102, "Refresh token is invalid, expired, or revoked", 401),
  REFRESH_TOKEN_REUSED(40103, "Refresh token was already used", 401),
  SESSION_NOT_FOUND(40402, "Session not found", 404),
  INVALID_ROLE(50001, "Role is invalid or not supported", 500);

  private final Integer code;
  private final String message;
  private final int status;

  AuthErrorCode(Integer code, String message, int status) {
    this.code = code;
    this.message = message;
    this.status = status;
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

  @Override
  public Integer getCode() {
    return code;
  }
}
