package com.vandunxg.file_processing.auth.domain.exception;

/**
 * Business rules the auth aggregates enforce themselves. Pure domain language: no HTTP status, no
 * response format, no i18n key. The application layer maps these onto its error catalog.
 */
public enum AuthRule {

  /** A role handed to a user must exist, be active, and not be soft-deleted. */
  ROLE_NOT_ASSIGNABLE,

  /** Email verification only applies to an account still pending verification. */
  USER_ALREADY_VERIFIED,

  /** A password reset requires an active or pending-verification account. */
  PASSWORD_RESET_NOT_ALLOWED,

  /** A soft-deleted user can no longer be disabled. */
  USER_ALREADY_DELETED,

  /** The email verification token is expired, already used, or otherwise unusable. */
  EMAIL_VERIFICATION_TOKEN_UNUSABLE,

  /** The password reset token is expired, already used, or otherwise unusable. */
  PASSWORD_RESET_TOKEN_UNUSABLE
}
