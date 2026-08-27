package com.vandunxg.file_processing.auth.infrastructure.metrics;

import com.vandunxg.file_processing.auth.application.capability.AuthMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MicrometerAuthMetrics implements AuthMetrics {

  private static final String COUNTER = "auth_events_total";

  private final MeterRegistry meterRegistry;

  @Override
  public void loginRateLimited() {
    increment("login", "rate_limited");
  }

  @Override
  public void loginSucceeded() {
    increment("login", "success");
  }

  @Override
  public void loginInvalidCredentials() {
    increment("login", "invalid_credentials");
  }

  @Override
  public void loginLocked() {
    increment("login", "locked");
  }

  @Override
  public void loginPendingVerification() {
    increment("login", "pending_verification");
  }

  @Override
  public void loginDisabled() {
    increment("login", "disabled");
  }

  @Override
  public void forgotPasswordRateLimited() {
    increment("forgot_password", "rate_limited");
  }

  @Override
  public void refreshRateLimited() {
    increment("refresh", "rate_limited");
  }

  @Override
  public void refreshTokenReused() {
    increment("refresh", "reused");
  }

  @Override
  public void passwordResetRequested() {
    increment("password_reset", "requested");
  }

  @Override
  public void passwordResetCompleted() {
    increment("password_reset", "completed");
  }

  @Override
  public void passwordResetRejected() {
    increment("password_reset", "rejected");
  }

  @Override
  public void tokenValidationFailed() {
    increment("token_validation", "failed");
  }

  private void increment(String operation, String outcome) {
    meterRegistry.counter(COUNTER, "operation", operation, "outcome", outcome).increment();
  }
}
