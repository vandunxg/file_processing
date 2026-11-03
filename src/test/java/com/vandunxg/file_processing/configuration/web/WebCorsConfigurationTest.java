package com.vandunxg.file_processing.configuration.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.List;

import jakarta.servlet.Filter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.CorsFilter;

/**
 * The refresh-token flow is cookie based, so a browser only reaches it when the preflight answers
 * with an explicit origin plus {@code Access-Control-Allow-Credentials: true}. These tests drive
 * the real {@code CorsFilter} the application registers, because the failure this guards against —
 * a wildcard origin silently disabling credentials — is invisible at the configuration level.
 */
class WebCorsConfigurationTest {

  private static final String FRONTEND_ORIGIN = "http://localhost:5173";
  private static final String REFRESH_PATH = "/api/v1/auth/refresh";

  @Test
  void answersPreflightWithTheCallingOriginAndCredentialsEnabled() throws Exception {
    MockHttpServletResponse response = preflight(FRONTEND_ORIGIN, REFRESH_PATH, "POST");

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
        .isEqualTo(FRONTEND_ORIGIN);
    assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS)).isEqualTo("true");
  }

  @Test
  void neverAnswersWithAWildcardOriginBecauseThatWouldDisableCookies() throws Exception {
    MockHttpServletResponse response = preflight(FRONTEND_ORIGIN, REFRESH_PATH, "POST");

    assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNotEqualTo("*");
  }

  @Test
  void allowsTheCsrfHeaderTheRefreshCallMustSend() throws Exception {
    MockHttpServletRequest request = preflightRequest(FRONTEND_ORIGIN, REFRESH_PATH, "POST");
    request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "x-csrf-token");
    MockHttpServletResponse response = new MockHttpServletResponse();

    corsFilter().doFilter(request, response, new MockFilterChain());

    assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS))
        .containsIgnoringCase("x-csrf-token");
  }

  @Test
  void exposesTheHeadersTheFrontEndActsOn() throws Exception {
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
    request.addHeader(HttpHeaders.ORIGIN, FRONTEND_ORIGIN);
    MockHttpServletResponse response = new MockHttpServletResponse();

    corsFilter().doFilter(request, response, new MockFilterChain());

    assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS))
        .contains("Retry-After")
        .contains("Content-Disposition");
  }

  @Test
  void rejectsAnOriginThatIsNotConfigured() throws Exception {
    MockHttpServletResponse response = preflight("https://evil.example.com", REFRESH_PATH, "POST");

    assertThat(response.getStatus()).isEqualTo(403);
    assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN)).isNull();
  }

  @Test
  void rejectsWildcardOriginsForCredentialedRequests() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new CorsProperties(
                    List.of("*"),
                    List.of("POST"),
                    List.of("X-CSRF-Token"),
                    List.of(),
                    true,
                    Duration.ofMinutes(30)))
        .withMessageContaining("explicit allowed origins");
  }

  @Test
  void rejectsWildcardOriginsWhenCredentialsAreDisabled() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new CorsProperties(
                    List.of("*"),
                    List.of("POST"),
                    List.of("X-CSRF-Token"),
                    List.of(),
                    false,
                    Duration.ofMinutes(30)))
        .withMessageContaining("explicit allowed origins");
  }

  @Test
  void coversTheConfiguredApiPath() throws Exception {
    FilterRegistrationBean<CorsFilter> registration =
        new WebCorsConfiguration()
            .corsFilterRegistration(properties(), new ApiProperties("/gateway", "v2"));
    MockHttpServletResponse response = new MockHttpServletResponse();

    registration
        .getFilter()
        .doFilter(
            preflightRequest(FRONTEND_ORIGIN, "/gateway/v2/auth/refresh", "POST"),
            response,
            new MockFilterChain());

    assertThat(response.getStatus()).isEqualTo(200);
    assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
        .isEqualTo(FRONTEND_ORIGIN);
  }

  @Test
  void coversEveryAuthRouteIncludingTheOnesSpringSecurityIgnores() throws Exception {
    for (String path :
        List.of(
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/register",
            "/api/v1/me",
            "/api/v1/file-imports")) {
      MockHttpServletResponse response = preflight(FRONTEND_ORIGIN, path, "POST");

      assertThat(response.getHeader(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN))
          .as("CORS must cover %s", path)
          .isEqualTo(FRONTEND_ORIGIN);
    }
  }

  @Test
  void runsBeforeEverySecurityFilterSoPreflightIsNeverAuthenticated() {
    assertThat(registration().getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
  }

  /**
   * Binds the exact keys {@code application.yml} ships. A renamed or mistyped key would otherwise
   * only surface as a startup failure in whichever environment first loads the file.
   */
  @Test
  void bindsTheShippedConfigurationKeys() {
    new ApplicationContextRunner()
        .withUserConfiguration(BindingHarness.class)
        .withPropertyValues(
            "app.web.cors.allowed-origins=http://localhost:5173,http://localhost:3000",
            "app.web.cors.allowed-methods=GET,POST,PUT,PATCH,DELETE,OPTIONS",
            "app.web.cors.allowed-headers=*",
            "app.web.cors.exposed-headers=Retry-After,Content-Disposition,Location",
            "app.web.cors.allow-credentials=true",
            "app.web.cors.max-age=PT30M")
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              CorsProperties bound = context.getBean(CorsProperties.class);

              assertThat(bound.allowedOrigins())
                  .containsExactly("http://localhost:5173", "http://localhost:3000");
              assertThat(bound.allowedMethods()).contains("OPTIONS");
              assertThat(bound.exposedHeaders()).contains("Retry-After", "Content-Disposition");
              assertThat(bound.allowCredentials()).isTrue();
              assertThat(bound.maxAge()).isEqualTo(Duration.ofMinutes(30));
            });
  }

  @Test
  void refusesToStartWithoutAnAllowedOrigin() {
    new ApplicationContextRunner()
        .withUserConfiguration(BindingHarness.class)
        .withPropertyValues(
            "app.web.cors.allowed-methods=GET",
            "app.web.cors.allowed-headers=*",
            "app.web.cors.allow-credentials=true",
            "app.web.cors.max-age=PT30M")
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void refusesToStartWithAWildcardCredentialedOrigin() {
    new ApplicationContextRunner()
        .withUserConfiguration(BindingHarness.class)
        .withPropertyValues(
            "app.web.cors.allowed-origins=*",
            "app.web.cors.allowed-methods=POST",
            "app.web.cors.allowed-headers=X-CSRF-Token",
            "app.web.cors.allow-credentials=true",
            "app.web.cors.max-age=PT30M")
        .run(context -> assertThat(context).hasFailed());
  }

  @EnableConfigurationProperties(CorsProperties.class)
  static class BindingHarness {}

  private static MockHttpServletResponse preflight(String origin, String path, String method)
      throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();
    corsFilter().doFilter(preflightRequest(origin, path, method), response, new MockFilterChain());
    return response;
  }

  private static MockHttpServletRequest preflightRequest(
      String origin, String path, String method) {
    MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", path);
    request.addHeader(HttpHeaders.ORIGIN, origin);
    request.addHeader(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method);
    return request;
  }

  private static Filter corsFilter() {
    return registration().getFilter();
  }

  private static org.springframework.boot.web.servlet.FilterRegistrationBean<
          org.springframework.web.filter.CorsFilter>
      registration() {
    return new WebCorsConfiguration()
        .corsFilterRegistration(properties(), new ApiProperties("/api", "v1"));
  }

  /** Mirrors the shipped defaults in {@code application.yml}. */
  private static CorsProperties properties() {
    return new CorsProperties(
        List.of("http://localhost:5173", "http://localhost:3000"),
        List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"),
        List.of("*"),
        List.of("Retry-After", "Content-Disposition", "Location"),
        true,
        Duration.ofMinutes(30));
  }
}
