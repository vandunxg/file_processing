package com.vandunxg.file_processing.auth.api.dto.response;

import java.time.Instant;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

public record SessionResponse(
    @Schema(description = "Session id") UUID sessionId,
    @Schema(description = "User agent captured at creation") String userAgent,
    @Schema(description = "Session creation time") Instant createdAt,
    @Schema(description = "Last time this session was used to refresh or renew tokens")
        Instant lastUsedAt,
    @Schema(description = "Session absolute expiry") Instant expiresAt,
    @Schema(description = "True when this session belongs to the caller's current bearer token")
        boolean current) {}
