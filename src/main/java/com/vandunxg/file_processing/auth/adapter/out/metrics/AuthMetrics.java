package com.vandunxg.file_processing.auth.adapter.out.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AuthMetrics {

  private static final String COUNTER = "auth_events_total";

  private final MeterRegistry meterRegistry;

  public void loginRateLimited() {
    increment("login", "rate_limited");
  }

  public void loginSucceeded() {
    increment("login", "success");
  }

  public void loginInvalidCredentials() {
    increment("login", "invalid_credentials");
  }

  public void loginLocked() {
    increment("login", "locked");
  }

  public void loginPendingVerification() {
    increment("login", "pending_verification");
  }

  public void loginDisabled() {
    increment("login", "disabled");
  }

  public void forgotPasswordRateLimited() {
    increment("forgot_password", "rate_limited");
  }

  public void refreshRateLimited() {
    increment("refresh", "rate_limited");
  }

  public void refreshTokenReused() {
    increment("refresh", "reused");
  }

  public void passwordResetRequested() {
    increment("password_reset", "requested");
  }

  public void passwordResetCompleted() {
    increment("password_reset", "completed");
  }

  public void passwordResetRejected() {
    increment("password_reset", "rejected");
  }

  public void tokenValidationFailed() {
    increment("token_validation", "failed");
  }

  private void increment(String operation, String outcome) {
    meterRegistry.counter(COUNTER, "operation", operation, "outcome", outcome).increment();
  }
}
