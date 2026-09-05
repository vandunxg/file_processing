package com.vandunxg.file_processing.auth.infrastructure.security;

import com.vandunxg.file_processing.configuration.security.SecurityFilterChainContributor;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.stereotype.Component;

/**
 * Installs the auth module's request filters: credential-version resolution first, then action
 * logging, both after bearer-token authentication has populated the security context.
 */
@NullMarked
@Component
@Order(AuthSecurityFilterChainContributor.ORDER)
@RequiredArgsConstructor
public class AuthSecurityFilterChainContributor implements SecurityFilterChainContributor {

  /**
   * Auth contributes first: every other module's filters can then assume the security context has
   * already been resolved to a real user.
   */
  public static final int ORDER = 100;

  private final CustomAuthenticationFilter customAuthenticationFilter;
  private final ActionLoggingFilter actionLoggingFilter;

  @Override
  public void contribute(HttpSecurity http) {
    http.addFilterAfter(customAuthenticationFilter, BearerTokenAuthenticationFilter.class);
    http.addFilterAfter(actionLoggingFilter, CustomAuthenticationFilter.class);
  }
}
