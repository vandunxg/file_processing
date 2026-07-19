package com.vandunxg.file_processing.auth.adapter.in.web.dto.response;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

public record LoginResponse(
    @Schema(description = "Token type", example = "Bearer") String tokenType,
    @Schema(description = "Signed JWT access token") String accessToken,
    @Schema(description = "Access token TTL in seconds") long expiresIn,
    @Schema(description = "Access token absolute expiry") Instant accessTokenExpiresAt,
    @Schema(description = "Opaque refresh token; store securely, rotate on every use")
        String refreshToken,
    @Schema(description = "Refresh token TTL in seconds") long refreshExpiresIn,
    @Schema(description = "Refresh token absolute expiry") Instant refreshTokenExpiresAt,
    @Schema(description = "Server-side session identifier") UUID sessionId,
    @Schema(description = "Authenticated user id") UUID userId) {}
