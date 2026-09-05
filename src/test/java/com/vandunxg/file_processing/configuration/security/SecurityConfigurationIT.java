package com.vandunxg.file_processing.configuration.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.vandunxg.file_processing.auth.infrastructure.security.ActionLoggingFilter;
import com.vandunxg.file_processing.auth.infrastructure.security.CustomAuthenticationFilter;
import com.vandunxg.file_processing.testsupport.AuthIntegrationTestBase;
import com.vandunxg.file_processing.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.FilterChainProxy;

@PostgresIntegrationTest
class SecurityConfigurationIT extends AuthIntegrationTestBase {

  @Autowired private FilterChainProxy filterChainProxy;
  @Autowired private ActionLoggingFilter actionLoggingFilter;
  @Autowired private CustomAuthenticationFilter customAuthenticationFilter;

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
}
