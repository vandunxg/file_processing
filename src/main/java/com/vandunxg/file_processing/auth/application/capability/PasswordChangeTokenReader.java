package com.vandunxg.file_processing.auth.application.capability;

import java.util.UUID;

/**
 * Resolves the user behind a password-change token presented in an {@code Authorization} header.
 *
 * <p>The forced first-login change is the one flow authorised by a token that is not a normal
 * access token, so the security chain cannot resolve the caller and the endpoint has to do it
 * itself. This contract keeps that step at the application boundary: the token happens to be a JWT
 * today, and swapping that is an infrastructure concern, not a controller change.
 */
public interface PasswordChangeTokenReader {

  /**
   * @param authorizationHeader the raw {@code Authorization} header value, or {@code null}
   * @return the user the token was issued for
   * @throws com.vandunxg.file_processing.auth.application.exception.AuthException when the header
   *     is missing, malformed, expired, or does not name a valid user
   */
  UUID readUserId(String authorizationHeader);
}
