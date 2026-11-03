package com.vandunxg.file_processing.auth.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.vandunxg.common.models.UserAuthentication;
import com.vandunxg.common.utils.Constants;
import com.vandunxg.file_processing.auth.application.capability.ActionLogEventPublisher;
import com.vandunxg.file_processing.auth.domain.model.ActionLog;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@ExtendWith(MockitoExtension.class)
class ActionLoggingFilterTest {

  @Mock private ActionLogEventPublisher actionLogEventPublisher;

  @Test
  void doFilterInternal_recordsFailedAuthenticatedRequestWithSanitizedError()
      throws ServletException, IOException {
    UUID userId = UUID.randomUUID();
    SecurityContextHolder.getContext()
        .setAuthentication(new UserAuthentication(jwt(userId), List.of(), userId));
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/customers");
    request.setServletPath("/api/v1/customers");
    request.setAttribute(CustomAuthenticationFilter.AUTHENTICATED_USERNAME_ATTRIBUTE, "operator");
    request.setContentType("application/json");
    request.setContent("{\"password\":\"secret\",\"name\":\"A\"}".getBytes());
    request.addHeader(HttpHeaders.USER_AGENT, "JUnit");
    request.addHeader("X-Real-IP", "10.0.0.1");
    MockHttpServletResponse response = new MockHttpServletResponse();
    RuntimeException exception = new RuntimeException("boom");
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    RequestContextHolder.currentRequestAttributes()
        .setAttribute(
            com.vandunxg.common.utils.Constants.EXCEPTION_MESSAGE,
            exception,
            org.springframework.web.context.request.RequestAttributes.SCOPE_REQUEST);

    ActionLoggingFilter filter = new ActionLoggingFilter(actionLogEventPublisher);

    filter.doFilterInternal(
        request,
        response,
        (servletRequest, servletResponse) ->
            ((jakarta.servlet.http.HttpServletResponse) servletResponse).setStatus(500));

    ArgumentCaptor<ActionLog> captor = ArgumentCaptor.forClass(ActionLog.class);
    verify(actionLogEventPublisher).publish(captor.capture());
    ActionLog log = captor.getValue();
    assertThat(log.getUserId()).isEqualTo(userId);
    assertThat(log.getUsername()).isEqualTo("operator");
    assertThat(log.getPath()).isEqualTo("/api/v1/customers");
    assertThat(log.getRequestMethod()).isEqualTo("POST");
    assertThat(log.getIpAddress()).isEqualTo("10.0.0.1");
    assertThat(log.getStatusCode()).isEqualTo(500);
    assertThat(log.getErrorMessage()).isEqualTo(RuntimeException.class.getName());
    assertThat(log.getRequestData()).contains("******").doesNotContain("secret");

    verify(actionLogEventPublisher).publish(any(ActionLog.class));
    SecurityContextHolder.clearContext();
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void doFilterInternal_redactsNestedAndTemporaryCredentialsFromFailedRequest()
      throws ServletException, IOException {
    UUID userId = UUID.randomUUID();
    SecurityContextHolder.getContext()
        .setAuthentication(new UserAuthentication(jwt(userId), List.of(), userId));
    MockHttpServletRequest request =
        new MockHttpServletRequest("POST", "/api/v1/users/id/reset-password");
    request.setServletPath("/api/v1/users/id/reset-password");
    request.setAttribute(CustomAuthenticationFilter.AUTHENTICATED_USERNAME_ATTRIBUTE, "operator");
    request.setContentType("application/json");
    request.setContent(
        "{\"temporaryPassword\":\"temporary-secret\",\"nested\":{\"token\":\"nested-secret\"}}"
            .getBytes());
    MockHttpServletResponse response = new MockHttpServletResponse();
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

    new ActionLoggingFilter(actionLogEventPublisher)
        .doFilterInternal(
            request,
            response,
            (servletRequest, servletResponse) ->
                ((jakarta.servlet.http.HttpServletResponse) servletResponse).setStatus(400));

    ArgumentCaptor<ActionLog> captor = ArgumentCaptor.forClass(ActionLog.class);
    verify(actionLogEventPublisher).publish(captor.capture());
    assertThat(captor.getValue().getRequestData())
        .doesNotContain("temporary-secret", "nested-secret")
        .contains("********");

    SecurityContextHolder.clearContext();
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void doFilterInternal_redactsSensitiveQueryParametersAndMalformedBodies()
      throws ServletException, IOException {
    UUID userId = UUID.randomUUID();
    SecurityContextHolder.getContext()
        .setAuthentication(new UserAuthentication(jwt(userId), List.of(), userId));
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/customers");
    request.setServletPath("/api/v1/customers");
    request.setContentType("application/json");
    request.setContent("{\"token\":\"body-secret\"".getBytes());
    request.addParameter("refreshToken", "query-secret");
    MockHttpServletResponse response = new MockHttpServletResponse();
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

    new ActionLoggingFilter(actionLogEventPublisher)
        .doFilterInternal(
            request,
            response,
            (servletRequest, servletResponse) ->
                ((jakarta.servlet.http.HttpServletResponse) servletResponse).setStatus(400));

    ArgumentCaptor<ActionLog> captor = ArgumentCaptor.forClass(ActionLog.class);
    verify(actionLogEventPublisher).publish(captor.capture());
    assertThat(captor.getValue().getRequestData()).doesNotContain("body-secret");
    assertThat(captor.getValue().getRequestParam()).doesNotContain("query-secret");

    SecurityContextHolder.clearContext();
    RequestContextHolder.resetRequestAttributes();
  }

  @Test
  void doFilterInternal_redactsPasswordVariantsAndSecretExceptionMessages()
      throws ServletException, IOException {
    UUID userId = UUID.randomUUID();
    SecurityContextHolder.getContext()
        .setAuthentication(new UserAuthentication(jwt(userId), List.of(), userId));
    MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/customers");
    request.setServletPath("/api/v1/customers");
    request.setContentType("application/json");
    request.setContent(
        "{\"confirmPassword\":\"confirm-secret\",\"resetToken\":\"reset-secret\",\"api_key\":\"api-secret\"}"
            .getBytes());
    request.addParameter("access_token", "access-secret");
    MockHttpServletResponse response = new MockHttpServletResponse();
    RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    RequestContextHolder.currentRequestAttributes()
        .setAttribute(
            Constants.EXCEPTION_MESSAGE,
            new IllegalStateException("exception-secret"),
            RequestAttributes.SCOPE_REQUEST);

    new ActionLoggingFilter(actionLogEventPublisher)
        .doFilterInternal(
            request,
            response,
            (servletRequest, servletResponse) ->
                ((jakarta.servlet.http.HttpServletResponse) servletResponse).setStatus(500));

    ArgumentCaptor<ActionLog> captor = ArgumentCaptor.forClass(ActionLog.class);
    verify(actionLogEventPublisher).publish(captor.capture());
    ActionLog log = captor.getValue();
    assertThat(log.getRequestData()).doesNotContain("confirm-secret", "reset-secret", "api-secret");
    assertThat(log.getRequestParam()).doesNotContain("access-secret");
    assertThat(log.getErrorMessage()).isEqualTo(IllegalStateException.class.getName());

    SecurityContextHolder.clearContext();
    RequestContextHolder.resetRequestAttributes();
  }

  private static Jwt jwt(UUID userId) {
    Instant now = Instant.parse("2026-07-27T00:00:00Z");
    return Jwt.withTokenValue("test-token")
        .header("alg", "RS256")
        .subject(userId.toString())
        .issuedAt(now)
        .expiresAt(now.plusSeconds(60))
        .build();
  }
}
