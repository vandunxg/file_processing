package com.vandunxg.file_processing.auth.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.vandunxg.file_processing.auth.adapter.out.metrics.AuthMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class MetricsJwtDecoderTest {

  @Test
  void recordsGenericTokenValidationFailuresBeforeRethrowing() {
    JwtDecoder delegate = mock(JwtDecoder.class);
    MeterRegistry registry = new SimpleMeterRegistry();
    AuthMetrics authMetrics = new AuthMetrics(registry);
    when(delegate.decode("invalid-jwt")).thenThrow(new BadJwtException("invalid"));
    MetricsJwtDecoder decoder = new MetricsJwtDecoder(delegate, authMetrics);

    assertThatThrownBy(() -> decoder.decode("invalid-jwt")).isInstanceOf(BadJwtException.class);

    assertThat(
            registry
                .find("auth_events_total")
                .tags("operation", "token_validation", "outcome", "failed")
                .counter())
        .isNotNull();
  }
}
