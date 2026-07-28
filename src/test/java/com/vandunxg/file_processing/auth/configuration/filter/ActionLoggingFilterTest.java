package com.vandunxg.file_processing.auth.configuration.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.vandunxg.common.models.UserAuthentication;
import com.vandunxg.file_processing.auth.application.port.out.ActionLogEventPublisherPort;
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
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@ExtendWith(MockitoExtension.class)
class ActionLoggingFilterTest {

  @Mock private ActionLogEventPublisherPort actionLogEventPublisherPort;

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

    ActionLoggingFilter filter = new ActionLoggingFilter(actionLogEventPublisherPort);

    filter.doFilterInternal(
        request,
        response,
        (servletRequest, servletResponse) ->
            ((jakarta.servlet.http.HttpServletResponse) servletResponse).setStatus(500));

    ArgumentCaptor<ActionLog> captor = ArgumentCaptor.forClass(ActionLog.class);
    verify(actionLogEventPublisherPort).publish(captor.capture());
    ActionLog log = captor.getValue();
    assertThat(log.getUserId()).isEqualTo(userId);
    assertThat(log.getUsername()).isEqualTo("operator");
    assertThat(log.getPath()).isEqualTo("/api/v1/customers");
    assertThat(log.getRequestMethod()).isEqualTo("POST");
    assertThat(log.getIpAddress()).isEqualTo("10.0.0.1");
    assertThat(log.getStatusCode()).isEqualTo(500);
    assertThat(log.getErrorMessage())
        .contains("java.lang.RuntimeException: boom")
        .contains("ActionLoggingFilterTest");
    assertThat(log.getRequestData()).contains("******").doesNotContain("secret");

    verify(actionLogEventPublisherPort).publish(any(ActionLog.class));
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
