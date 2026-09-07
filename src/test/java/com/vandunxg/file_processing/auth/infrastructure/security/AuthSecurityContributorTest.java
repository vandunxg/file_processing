package com.vandunxg.file_processing.auth.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.vandunxg.file_processing.configuration.security.ModuleSecurityContributor;
import org.junit.jupiter.api.Test;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.oauth2.jwt.JwtDecoder;

class AuthSecurityContributorTest {

  @SuppressWarnings("unchecked")
  private final ModuleSecurityContributor contributor =
      new AuthSecurityContributor(
          mock(JwtDecoder.class),
          mock(Converter.class),
          mock(CustomAuthenticationFilter.class),
          mock(ActionLoggingFilter.class));

  /**
   * The composition root no longer knows these routes, so this list is the only place that records
   * which auth endpoints are reachable without a bearer token. A path silently dropping out of it
   * would make a public endpoint start returning 401.
   */
  @Test
  void declaresEveryAuthEndpointServedWithoutAuthentication() {
    assertThat(contributor.publicPaths())
        .containsExactly(
            "/api/v1/auth/register",
            "/api/v1/auth/verify-email",
            "/api/v1/auth/resend-verification",
            "/api/v1/auth/forgot-password",
            "/api/v1/auth/reset-password",
            "/api/v1/auth/complete-password-change",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/certificate/.well-known/jwks.json");
  }
}
