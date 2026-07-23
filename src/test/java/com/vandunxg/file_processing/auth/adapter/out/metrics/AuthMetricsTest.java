package com.vandunxg.file_processing.auth.adapter.out.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class AuthMetricsTest {

  @Test
  void recordsOnlyFixedAuthenticationOutcomes() {
    MeterRegistry registry = new SimpleMeterRegistry();
    AuthMetrics metrics = new AuthMetrics(registry);

    metrics.loginRateLimited();
    metrics.forgotPasswordRateLimited();
    metrics.refreshRateLimited();
    metrics.refreshTokenReused();
    metrics.passwordResetRequested();
    metrics.passwordResetCompleted();
    metrics.passwordResetRejected();
    metrics.tokenValidationFailed();

    assertCounter(registry, "login", "rate_limited");
    assertCounter(registry, "forgot_password", "rate_limited");
    assertCounter(registry, "refresh", "rate_limited");
    assertCounter(registry, "refresh", "reused");
    assertCounter(registry, "password_reset", "requested");
    assertCounter(registry, "password_reset", "completed");
    assertCounter(registry, "password_reset", "rejected");
    assertCounter(registry, "token_validation", "failed");
  }

  @Test
  void recordsFixedLoginOutcomes() {
    MeterRegistry registry = new SimpleMeterRegistry();
    AuthMetrics metrics = new AuthMetrics(registry);

    metrics.loginSucceeded();
    metrics.loginInvalidCredentials();
    metrics.loginLocked();
    metrics.loginPendingVerification();
    metrics.loginDisabled();

    assertCounter(registry, "login", "success");
    assertCounter(registry, "login", "invalid_credentials");
    assertCounter(registry, "login", "locked");
    assertCounter(registry, "login", "pending_verification");
    assertCounter(registry, "login", "disabled");
  }

  private static void assertCounter(MeterRegistry registry, String operation, String outcome) {
    assertThat(
            registry
                .get("auth_events_total")
                .tags("operation", operation, "outcome", outcome)
                .counter()
                .count())
        .isEqualTo(1);
  }
}
