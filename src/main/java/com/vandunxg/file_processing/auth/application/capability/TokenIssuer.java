package com.vandunxg.file_processing.auth.application.capability;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TokenIssuer {

  IssuedAccessToken issue(
      UUID userId,
      UUID sessionId,
      int credentialVersion,
      List<String> roles,
      List<String> permissions,
      Instant now);

  IssuedPasswordChangeToken issuePasswordChange(UUID userId, int credentialVersion, Instant now);

  record IssuedAccessToken(String token, Instant issuedAt, Instant expiresAt) {}

  record IssuedPasswordChangeToken(String token, Instant issuedAt, Instant expiresAt) {}
}
