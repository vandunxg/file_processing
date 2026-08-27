package com.vandunxg.file_processing.auth.api.dto.response;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

public record LoginResponse(
    @Schema(description = "Forced password change status") String status,
    @Schema(description = "Signed JWT for completing a forced password change", format = "password")
        String passwordChangeToken,
    @Schema(description = "Token type", example = "Bearer") String tokenType,
    @Schema(description = "Signed JWT access token") String accessToken,
    @Schema(description = "Issued JWT TTL in seconds") long expiresIn,
    @Schema(description = "Access token absolute expiry") Instant accessTokenExpiresAt,
    @Schema(description = "Refresh token TTL in seconds") Long refreshExpiresIn,
    @Schema(description = "Refresh token absolute expiry") Instant refreshTokenExpiresAt,
    @Schema(description = "Server-side session identifier") UUID sessionId,
    @Schema(description = "Authenticated user id") UUID userId) {}
