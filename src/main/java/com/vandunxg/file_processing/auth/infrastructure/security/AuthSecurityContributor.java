package com.vandunxg.file_processing.auth.infrastructure.security;

import java.util.List;

import com.vandunxg.file_processing.configuration.security.ModuleSecurityContributor;
import com.vandunxg.file_processing.configuration.web.ApiProperties;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.annotation.Order;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.stereotype.Component;

/**
 * Declares what the auth module installs into the application-wide security chain: the endpoints it
 * serves anonymously, the JWT resource-server mechanism that authenticates every other request, and
 * its own request filters — credential-version resolution first, then action logging, both after
 * bearer-token authentication has populated the security context.
 */
@NullMarked
@Component
@Order(AuthSecurityContributor.ORDER)
@RequiredArgsConstructor
public class AuthSecurityContributor implements ModuleSecurityContributor {

  /**
   * Auth contributes first: every other module's filters can then assume the security context has
   * already been resolved to a real user.
   */
  public static final int ORDER = 100;

  private final JwtDecoder jwtDecoder;
  private final Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter;
  private final CustomAuthenticationFilter customAuthenticationFilter;
  private final ActionLoggingFilter actionLoggingFilter;
  private final ApiProperties apiProperties;

  @Override
  public List<String> publicPaths() {
    String authPath = apiProperties.authPath();
    return List.of(
        authPath + "/register",
        authPath + "/verify-email",
        authPath + "/resend-verification",
        authPath + "/forgot-password",
        authPath + "/reset-password",
        authPath + "/complete-password-change",
        authPath + "/login",
        authPath + "/refresh",
        apiProperties.basePath() + "/certificate/.well-known/jwks.json");
  }

  @Override
  public void contribute(HttpSecurity http) throws Exception {
    JwtAuthenticationProvider jwtAuthenticationProvider = new JwtAuthenticationProvider(jwtDecoder);
    jwtAuthenticationProvider.setJwtAuthenticationConverter(jwtAuthenticationConverter);
    http.oauth2ResourceServer(
        oauth2 ->
            oauth2.authenticationManagerResolver(
                request -> jwtAuthenticationProvider::authenticate));

    http.addFilterAfter(customAuthenticationFilter, BearerTokenAuthenticationFilter.class);
    http.addFilterAfter(actionLoggingFilter, CustomAuthenticationFilter.class);
  }
}
