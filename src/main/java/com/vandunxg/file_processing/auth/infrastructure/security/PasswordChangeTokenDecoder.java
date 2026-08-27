package com.vandunxg.file_processing.auth.infrastructure.security;

import com.vandunxg.file_processing.auth.application.exception.AuthErrorCode;
import com.vandunxg.file_processing.auth.application.exception.AuthException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

@Component
public class PasswordChangeTokenDecoder {

  private final JwtDecoder jwtDecoder;

  public PasswordChangeTokenDecoder(@Qualifier("passwordChangeJwtDecoder") JwtDecoder jwtDecoder) {
    this.jwtDecoder = jwtDecoder;
  }

  public Jwt decode(String authorization) {
    if (authorization == null || !authorization.startsWith("Bearer ")) {
      throw new AuthException(AuthErrorCode.AUTH_PASSWORD_CHANGE_TOKEN_INVALID);
    }
    try {
      return jwtDecoder.decode(authorization.substring("Bearer ".length()));
    } catch (JwtException e) {
      throw new AuthException(AuthErrorCode.AUTH_PASSWORD_CHANGE_TOKEN_INVALID);
    }
  }
}
