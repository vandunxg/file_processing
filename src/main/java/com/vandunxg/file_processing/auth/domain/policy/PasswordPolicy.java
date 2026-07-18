package com.vandunxg.file_processing.auth.domain.policy;

public final class PasswordPolicy {

  private static final int MIN_CODE_POINTS = 8;
  private static final int MAX_CODE_POINTS = 128;

  public ValidationResult validate(
      String password, String normalizedUsername, String normalizedEmail) {
    if (password == null || password.isBlank()) {
      return new ValidationResult(false, Reason.BLANK);
    }

    int codePointCount = password.codePointCount(0, password.length());
    if (codePointCount < MIN_CODE_POINTS) {
      return new ValidationResult(false, Reason.TOO_SHORT);
    }
    if (codePointCount > MAX_CODE_POINTS) {
      return new ValidationResult(false, Reason.TOO_LONG);
    }
    if (password.equalsIgnoreCase(normalizedUsername)) {
      return new ValidationResult(false, Reason.MATCHES_USERNAME);
    }
    if (password.equalsIgnoreCase(normalizedEmail)) {
      return new ValidationResult(false, Reason.MATCHES_EMAIL);
    }
    return new ValidationResult(true, null);
  }

  public enum Reason {
    BLANK,
    TOO_SHORT,
    TOO_LONG,
    MATCHES_USERNAME,
    MATCHES_EMAIL
  }

  public record ValidationResult(boolean valid, Reason reason) {}
}
