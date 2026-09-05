package com.vandunxg.file_processing.auth.application.result;

import java.time.Instant;
import java.util.UUID;

import lombok.Builder;

/**
 * Outcome of a login or a refresh. Two shapes share this type: an account with a temporary password
 * gets only {@code status} and {@code passwordChangeToken}, everything else gets the issued token
 * pair. The builder is what keeps both shapes readable at the call site.
 */
@Builder
public record LoginResult(
    String status,
    String passwordChangeToken,
    String tokenType,
    String accessToken,
    long expiresIn,
    Instant accessTokenExpiresAt,
    String refreshToken,
    Long refreshExpiresIn,
    Instant refreshTokenExpiresAt,
    UUID sessionId,
    UUID userId) {}
