package com.vandunxg.file_processing.auth.domain.exception;

import com.vandunxg.common.models.error.ResponseError;

public enum AuthErrorCode implements ResponseError {
  PASSWORD_POLICY_VIOLATION(40001, "Password does not meet policy requirements", 422),
  PASSWORD_CONFIRMATION_MISMATCH(40003, "Password confirmation does not match", 422),
  PASSWORD_SAME_AS_CURRENT(40004, "New password must differ from the current password", 400),
  PASSWORD_RESET_TOKEN_INVALID(
      40005, "Password reset token is invalid, expired, or already used", 400),
  PASSWORD_RESET_NOT_ALLOWED(40006, "Password reset is not allowed for this account", 400),
  USERNAME_ALREADY_EXISTS(40902, "Username already exists", 409),
  EMAIL_ALREADY_EXISTS(40903, "Email already exists", 409),
  AUTH_RATE_LIMITED(42901, "Too many requests. Please try again later", 429),
  EMAIL_VERIFICATION_TOKEN_INVALID(
      40002, "Email verification token is invalid, expired, or already used", 400),
  USER_ALREADY_VERIFIED(40907, "Email has already been verified", 409),
  USER_NOT_FOUND(40401, "User not found", 404),
  CURRENT_PASSWORD_INVALID(40007, "Current password is invalid", 400),
  PASSWORD_REUSE_NOT_ALLOWED(40904, "New password must differ from the current password", 409),
  INVALID_CREDENTIALS(40101, "Invalid username or password", 401),
  ACCOUNT_LOCKED(40301, "Account is locked", 403),
  EMAIL_VERIFICATION_REQUIRED(40302, "Please verify your email before logging in", 403),
  CSRF_TOKEN_INVALID(40303, "CSRF token is invalid", 403),
  REFRESH_TOKEN_INVALID(40102, "Refresh token is invalid, expired, or revoked", 401),
  REFRESH_TOKEN_REUSED(40103, "Refresh token was already used", 401),
  PASSWORD_CHANGE_TOKEN_INVALID(40106, "Password change token is invalid, expired, or stale", 401),
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
