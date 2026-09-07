package com.vandunxg.file_processing.auth.application.exception;

import com.vandunxg.common.models.error.ResponseError;
import com.vandunxg.file_processing.auth.domain.exception.AuthRule;

public enum AuthErrorCode implements ResponseError {
  AUTH_PASSWORD_POLICY_VIOLATION(42202, "Password does not meet policy requirements", 422),
  AUTH_PASSWORD_CONFIRMATION_MISMATCH(42203, "Password confirmation does not match", 422),
  AUTH_PASSWORD_SAME_AS_CURRENT(40004, "New password must differ from the current password", 400),
  AUTH_PASSWORD_RESET_TOKEN_INVALID(
      40005, "Password reset token is invalid, expired, or already used", 400),
  AUTH_PASSWORD_RESET_NOT_ALLOWED(40006, "Password reset is not allowed for this account", 400),
  AUTH_USERNAME_ALREADY_EXISTS(40902, "Username already exists", 409),
  AUTH_EMAIL_ALREADY_EXISTS(40903, "Email already exists", 409),
  AUTH_RATE_LIMITED(42901, "Too many requests. Please try again later", 429),
  AUTH_EMAIL_VERIFICATION_TOKEN_INVALID(
      40002, "Email verification token is invalid, expired, or already used", 400),
  USER_ALREADY_VERIFIED(40907, "Email has already been verified", 409),
  USER_NOT_FOUND(40401, "User not found", 404),
  AUTH_CURRENT_PASSWORD_INVALID(40007, "Current password is invalid", 400),
  AUTH_PASSWORD_REUSE_NOT_ALLOWED(40904, "New password must differ from the current password", 409),
  AUTH_INVALID_CREDENTIALS(40101, "Invalid username or password", 401),
  AUTH_ACCOUNT_LOCKED(40301, "Account is locked", 403),
  AUTH_EMAIL_VERIFICATION_REQUIRED(40302, "Please verify your email before logging in", 403),
  AUTH_CSRF_TOKEN_INVALID(40303, "CSRF token is invalid", 403),
  AUTH_REFRESH_TOKEN_INVALID(40102, "Refresh token is invalid, expired, or revoked", 401),
  AUTH_REFRESH_TOKEN_REUSED(40103, "Refresh token was already used", 401),
  AUTH_PASSWORD_CHANGE_TOKEN_INVALID(
      40106, "Password change token is invalid, expired, or stale", 401),
  AUTH_SESSION_NOT_FOUND(40402, "Session not found", 404),
  ROLE_INVALID(42201, "Role is invalid or not supported", 422),
  ROLE_NOT_FOUND(40403, "Role not found", 404),
  ROLE_CODE_ALREADY_EXISTS(40908, "Role code already exists", 409),
  ROLE_INHERITANCE_CYCLE(40909, "Role inheritance would create a cycle", 409),
  ROLE_STILL_ASSIGNED(40910, "Role is still assigned to users", 409),
  ROLE_IS_CONST(40911, "System role cannot be changed this way", 409),
  AUTH_LAST_ACTIVE_ADMIN(40912, "At least one active administrator is required", 409),
  ROLE_IS_ACTIVE(40913, "Role must be inactive before deletion", 409);

  private final Integer code;
  private final String message;
  private final int status;

  AuthErrorCode(Integer code, String message, int status) {
    this.code = code;
    this.message = message;
    this.status = status;
  }

  /** Maps an aggregate rule violation onto the module error catalog. */
  public static AuthErrorCode from(AuthRule rule) {
    return switch (rule) {
      case ROLE_NOT_ASSIGNABLE -> ROLE_INVALID;
      case USER_ALREADY_VERIFIED -> USER_ALREADY_VERIFIED;
      case PASSWORD_RESET_NOT_ALLOWED -> AUTH_PASSWORD_RESET_NOT_ALLOWED;
      case USER_ALREADY_DELETED -> USER_NOT_FOUND;
      case EMAIL_VERIFICATION_TOKEN_UNUSABLE -> AUTH_EMAIL_VERIFICATION_TOKEN_INVALID;
      case PASSWORD_RESET_TOKEN_UNUSABLE -> AUTH_PASSWORD_RESET_TOKEN_INVALID;
    };
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
