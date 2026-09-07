package com.vandunxg.file_processing.configuration.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vandunxg.file_processing.auth.infrastructure.security.ActionLoggingFilter;
import com.vandunxg.file_processing.auth.infrastructure.security.CustomAuthenticationFilter;
import com.vandunxg.file_processing.testsupport.AuthIntegrationTestBase;
import com.vandunxg.file_processing.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;

@PostgresIntegrationTest
@AutoConfigureMockMvc
class SecurityConfigurationIT extends AuthIntegrationTestBase {

  @Autowired private FilterChainProxy filterChainProxy;
  @Autowired private ActionLoggingFilter actionLoggingFilter;
  @Autowired private CustomAuthenticationFilter customAuthenticationFilter;
  @Autowired private MockMvc mockMvc;

  @Test
  void runsActionLoggingAfterBearerAuthentication() {
    var filters = filterChainProxy.getFilters("/api/v1/me");
    int bearerFilterIndex =
        filters.indexOf(
            filters.stream()
                .filter(BearerTokenAuthenticationFilter.class::isInstance)
                .findFirst()
                .orElseThrow());

    assertThat(filters.indexOf(actionLoggingFilter)).isGreaterThan(bearerFilterIndex);
  }

  @Test
  void runsCustomAuthenticationAfterBearerAuthentication() {
    var filters = filterChainProxy.getFilters("/api/v1/me");
    int bearerFilterIndex =
        filters.indexOf(
            filters.stream()
                .filter(BearerTokenAuthenticationFilter.class::isInstance)
                .findFirst()
                .orElseThrow());

    assertThat(filters.indexOf(customAuthenticationFilter)).isGreaterThan(bearerFilterIndex);
  }

  /**
   * A path the auth module contributed through {@code publicPaths()} must stay outside the security
   * filter chain entirely, exactly as the composition root's former hardcoded list left it. {@code
   * WebSecurity.ignoring()} registers a chain with no filters, so an empty list here is the direct
   * evidence that the path bypasses the chain rather than merely being permitted.
   */
  @Test
  void excludesAContributedPublicPathFromTheSecurityFilterChain() {
    assertThat(filterChainProxy.getFilters("/api/v1/auth/login")).isEmpty();
  }

  /**
   * The root no longer installs an authentication mechanism; auth contributes it. If that
   * contribution is lost, /api/** is still .authenticated() so the app fails closed — this asserts
   * it did not silently fail closed.
   */
  @Test
  void installsBearerTokenAuthenticationThroughAModuleContribution() {
    assertThat(filterChainProxy.getFilters("/api/v1/me"))
        .anyMatch(BearerTokenAuthenticationFilter.class::isInstance);
  }

  /** A path no module contributed still needs a bearer token. */
  @Test
  void requiresAuthenticationForPathsNoModuleContributed() throws Exception {
    mockMvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
  }
}
