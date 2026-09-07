package com.vandunxg.file_processing.auth.infrastructure.security;

import java.util.UUID;

import com.vandunxg.file_processing.auth.application.capability.PasswordChangeTokenReader;
import com.vandunxg.file_processing.auth.application.exception.AuthErrorCode;
import com.vandunxg.file_processing.auth.application.exception.AuthException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/** Reads the password-change token as a JWT signed with the dedicated password-change key. */
@Component
public class JwtPasswordChangeTokenReader implements PasswordChangeTokenReader {

  private static final String BEARER_PREFIX = "Bearer ";

  private final JwtDecoder jwtDecoder;

  public JwtPasswordChangeTokenReader(
      @Qualifier("passwordChangeJwtDecoder") JwtDecoder jwtDecoder) {
    this.jwtDecoder = jwtDecoder;
  }

  @Override
  public UUID readUserId(String authorizationHeader) {
    if (authorizationHeader == null || !authorizationHeader.startsWith(BEARER_PREFIX)) {
      throw invalid();
    }
    Jwt jwt;
    try {
      jwt = jwtDecoder.decode(authorizationHeader.substring(BEARER_PREFIX.length()));
    } catch (JwtException exception) {
      throw invalid();
    }
    // A token that decodes but carries no usable subject is the same failure to the caller as a
    // token that does not decode: rejecting it as invalid keeps it off the 500 path.
    try {
      return UUID.fromString(jwt.getSubject());
    } catch (IllegalArgumentException | NullPointerException exception) {
      throw invalid();
    }
  }

  private static AuthException invalid() {
    return new AuthException(AuthErrorCode.AUTH_PASSWORD_CHANGE_TOKEN_INVALID);
  }
}
