package com.vandunxg.file_processing.auth.infrastructure.security;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import com.vandunxg.file_processing.auth.domain.SessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Rejects any access token whose {@code sid} claim no longer points at a live session in the
 * hot-path store. This is what makes logout, revoke-all, and session revocation take effect
 * immediately instead of waiting for the JWT to expire.
 */
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-SESSION-ALLOWLIST-VALIDATOR")
public class SessionAllowListJwtValidator implements OAuth2TokenValidator<Jwt> {

  private final SessionRepository sessionRepository;
  private final Clock clock;

  @Override
  public OAuth2TokenValidatorResult validate(Jwt token) {
    String sidClaim = token.getClaimAsString("sid");
    if (sidClaim == null || sidClaim.isBlank()) {
      return OAuth2TokenValidatorResult.failure(
          new OAuth2Error("invalid_token", "Missing sid claim", null));
    }
    UUID sid;
    try {
      sid = UUID.fromString(sidClaim);
    } catch (IllegalArgumentException e) {
      return OAuth2TokenValidatorResult.failure(
          new OAuth2Error("invalid_token", "Malformed sid claim", null));
    }
    Instant now = Instant.now(clock);
    return sessionRepository
        .findActiveById(sid, now)
        .map(s -> OAuth2TokenValidatorResult.success())
        .orElseGet(
            () -> {
              log.warn("[validate] session not active sid={}", sid);
              return OAuth2TokenValidatorResult.failure(
                  new OAuth2Error("invalid_token", "Session revoked or expired", null));
            });
  }
}
