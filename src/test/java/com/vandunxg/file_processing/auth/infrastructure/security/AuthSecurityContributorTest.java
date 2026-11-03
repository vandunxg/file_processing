package com.vandunxg.file_processing.auth.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.vandunxg.file_processing.configuration.security.ModuleSecurityContributor;
import com.vandunxg.file_processing.configuration.web.ApiProperties;
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
          mock(ActionLoggingFilter.class),
          new ApiProperties("/gateway", "v2"));

  /**
   * The composition root no longer knows these routes, so this list is the only place that records
   * which auth endpoints are reachable without a bearer token. A path silently dropping out of it
   * would make a public endpoint start returning 401.
   */
  @Test
  void declaresEveryAuthEndpointServedWithoutAuthentication() {
    assertThat(contributor.publicPaths())
        .containsExactly(
            "/gateway/v2/auth/register",
            "/gateway/v2/auth/verify-email",
            "/gateway/v2/auth/resend-verification",
            "/gateway/v2/auth/forgot-password",
            "/gateway/v2/auth/reset-password",
            "/gateway/v2/auth/complete-password-change",
            "/gateway/v2/auth/login",
            "/gateway/v2/auth/refresh",
            "/gateway/v2/certificate/.well-known/jwks.json");
  }
}
