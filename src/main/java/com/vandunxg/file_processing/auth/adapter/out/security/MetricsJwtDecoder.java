package com.vandunxg.file_processing.auth.adapter.out.security;

import com.vandunxg.file_processing.auth.adapter.out.metrics.AuthMetrics;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

@RequiredArgsConstructor
public class MetricsJwtDecoder implements JwtDecoder {

  private final JwtDecoder delegate;
  private final AuthMetrics authMetrics;

  @Override
  public Jwt decode(String token) throws JwtException {
    try {
      return delegate.decode(token);
    } catch (JwtException e) {
      authMetrics.tokenValidationFailed();
      throw e;
    }
  }
}
