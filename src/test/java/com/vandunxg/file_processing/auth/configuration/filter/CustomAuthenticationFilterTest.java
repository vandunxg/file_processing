package com.vandunxg.file_processing.auth.configuration.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.vandunxg.common.models.UserAuthentication;
import com.vandunxg.common.web.support.CustomAuthenticationEntryPoint;
import com.vandunxg.file_processing.auth.application.port.in.ResolveRequestAuthenticationUseCase;
import com.vandunxg.file_processing.auth.application.result.RequestAuthenticationResult;
import com.vandunxg.file_processing.auth.domain.exception.AuthDomainException;
import com.vandunxg.file_processing.auth.domain.exception.AuthErrorCode;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

@ExtendWith(MockitoExtension.class)
class CustomAuthenticationFilterTest {

  @Mock private ResolveRequestAuthenticationUseCase resolveRequestAuthenticationUseCase;
  @Mock private CustomAuthenticationEntryPoint customAuthenticationEntryPoint;
  @Mock private FilterChain filterChain;

  private CustomAuthenticationFilter filter;

  @BeforeEach
  void setUp() {
    filter =
        new CustomAuthenticationFilter(
            resolveRequestAuthenticationUseCase, customAuthenticationEntryPoint);
    SecurityContextHolder.clearContext();
  }

  @AfterEach
  void tearDown() {
    SecurityContextHolder.clearContext();
  }

  @Test
  void refreshesAuthoritiesFromTheApplicationUseCase() throws Exception {
    UUID userId = UUID.randomUUID();
    Jwt jwt = jwt(userId);
    SecurityContextHolder.getContext()
        .setAuthentication(new UserAuthentication(jwt, List.of(), userId));
    when(resolveRequestAuthenticationUseCase.resolve(userId))
        .thenReturn(new RequestAuthenticationResult(userId, "operator01", List.of("file:read")));

    MockHttpServletRequest request = new MockHttpServletRequest();
    filter.doFilter(request, new MockHttpServletResponse(), filterChain);

    Authentication refreshed = SecurityContextHolder.getContext().getAuthentication();
    assertThat(refreshed).isInstanceOf(UserAuthentication.class);
    assertThat(refreshed.getPrincipal()).isSameAs(jwt);
    assertThat(request.getAttribute(CustomAuthenticationFilter.AUTHENTICATED_USERNAME_ATTRIBUTE))
        .isEqualTo("operator01");
    assertThat(refreshed.getAuthorities())
        .extracting(GrantedAuthority::getAuthority)
        .containsExactly("file:read");
    verify(filterChain).doFilter(any(), any());
  }

  @Test
  void skipsResolutionWhenContextDoesNotContainUserAuthentication() throws Exception {
    filter.doFilter(new MockHttpServletRequest(), new MockHttpServletResponse(), filterChain);

    verifyNoInteractions(resolveRequestAuthenticationUseCase);
    verify(filterChain).doFilter(any(), any());
  }

  @Test
  void delegatesInvalidCredentialsToTheAuthenticationEntryPoint() throws Exception {
    UUID userId = UUID.randomUUID();
    Jwt jwt = jwt(userId);
    SecurityContextHolder.getContext()
        .setAuthentication(new UserAuthentication(jwt, List.of(), userId));
    when(resolveRequestAuthenticationUseCase.resolve(userId))
        .thenThrow(new AuthDomainException(AuthErrorCode.INVALID_CREDENTIALS));
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpServletResponse response = new MockHttpServletResponse();

    filter.doFilter(request, response, filterChain);

    verify(customAuthenticationEntryPoint)
        .commence(any(), any(), any(AuthenticationException.class));
    verifyNoInteractions(filterChain);
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
