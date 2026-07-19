package com.vandunxg.file_processing.auth.adapter.out.security;

import java.util.UUID;

import com.vandunxg.file_processing.auth.application.port.out.CredentialVersionCachePort;
import com.vandunxg.file_processing.auth.application.port.out.UserRepositoryPort;
import com.vandunxg.file_processing.auth.domain.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

@RequiredArgsConstructor
@Slf4j(topic = "AUTH-CV-VALIDATOR")
public class CredentialVersionJwtValidator implements OAuth2TokenValidator<Jwt> {

  private final CredentialVersionCachePort cachePort;
  private final UserRepositoryPort userRepositoryPort;

  @Override
  public OAuth2TokenValidatorResult validate(Jwt token) {
    Object cvRaw = token.getClaim("cv");
    if (!(cvRaw instanceof Number)) {
      return OAuth2TokenValidatorResult.failure(
          new OAuth2Error("invalid_token", "Missing cv claim", null));
    }
    String subject = token.getSubject();
    UUID userId;
    try {
      userId = UUID.fromString(subject);
    } catch (Exception e) {
      return OAuth2TokenValidatorResult.failure(
          new OAuth2Error("invalid_token", "Malformed subject", null));
    }
    int cvClaim = ((Number) cvRaw).intValue();
    int currentCv =
        cachePort
            .get(userId)
            .orElseGet(
                () -> {
                  int cv =
                      userRepositoryPort
                          .findById(userId)
                          .map(User::getCredentialVersion)
                          .orElse(-1);
                  if (cv >= 1) {
                    cachePort.put(userId, cv);
                  }
                  return cv;
                });
    if (currentCv < 1) {
      return OAuth2TokenValidatorResult.failure(
          new OAuth2Error("invalid_token", "User not found", null));
    }
    if (currentCv != cvClaim) {
      log.debug("[validate] cv mismatch userId={} claim={} current={}", userId, cvClaim, currentCv);
      return OAuth2TokenValidatorResult.failure(
          new OAuth2Error("invalid_token", "Credential version stale", null));
    }
    return OAuth2TokenValidatorResult.success();
  }
}
