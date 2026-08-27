package com.vandunxg.file_processing.auth.application.capability;

/**
 * Counters for security-relevant auth outcomes. Declared here so application services stay free of
 * a metrics technology; the Micrometer implementation lives in {@code infrastructure/metrics}.
 */
public interface AuthMetrics {

  void loginRateLimited();

  void loginSucceeded();

  void loginInvalidCredentials();

  void loginLocked();

  void loginPendingVerification();

  void loginDisabled();

  void forgotPasswordRateLimited();

  void refreshRateLimited();

  void refreshTokenReused();

  void passwordResetRequested();

  void passwordResetCompleted();

  void passwordResetRejected();

  void tokenValidationFailed();
}
