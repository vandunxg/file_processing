package com.vandunxg.file_processing.auth.configuration.filter;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.vandunxg.common.models.UserAuthentication;
import com.vandunxg.common.web.support.CustomAuthenticationEntryPoint;
import com.vandunxg.file_processing.auth.application.port.in.ResolveRequestAuthenticationUseCase;
import com.vandunxg.file_processing.auth.application.result.RequestAuthenticationResult;
import com.vandunxg.file_processing.auth.configuration.security.AuthenticatedUser;
import com.vandunxg.file_processing.auth.domain.exception.AuthDomainException;
import com.vandunxg.file_processing.auth.domain.exception.AuthErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@NullMarked
@Component
@RequiredArgsConstructor
public class CustomAuthenticationFilter extends OncePerRequestFilter {

  static final String AUTHENTICATED_USERNAME_ATTRIBUTE =
      CustomAuthenticationFilter.class.getName() + ".username";
  private static final String SESSION_ID_CLAIM = "sid";

  private final ResolveRequestAuthenticationUseCase resolveRequestAuthenticationUseCase;
  private final CustomAuthenticationEntryPoint customAuthenticationEntryPoint;

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    return !(SecurityContextHolder.getContext().getAuthentication() instanceof UserAuthentication);
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    UserAuthentication authentication =
        (UserAuthentication) SecurityContextHolder.getContext().getAuthentication();
    RequestAuthenticationResult result;
    try {
      result = resolveRequestAuthenticationUseCase.resolve(authentication.getUserId());
    } catch (AuthDomainException exception) {
      if (exception.getError() != AuthErrorCode.INVALID_CREDENTIALS) {
        throw exception;
      }
      SecurityContextHolder.clearContext();
      customAuthenticationEntryPoint.commence(
          request,
          response,
          new AuthenticationCredentialsNotFoundException("Invalid credentials", exception));
      return;
    }
    List<SimpleGrantedAuthority> authorities =
        result.permissions().stream().map(SimpleGrantedAuthority::new).toList();
    Jwt token = (Jwt) authentication.getPrincipal();
    UUID sessionId =
        UUID.fromString(Objects.requireNonNull(token.getClaimAsString(SESSION_ID_CLAIM)));
    AuthenticatedUser principal =
        new AuthenticatedUser(result.userId(), result.username(), sessionId, authorities);

    request.setAttribute(AUTHENTICATED_USERNAME_ATTRIBUTE, result.username());
    SecurityContextHolder.getContext()
        .setAuthentication(
            new UserAuthentication(
                principal, token, authorities, result.userId(), token.getTokenValue()));
    filterChain.doFilter(request, response);
  }
}
