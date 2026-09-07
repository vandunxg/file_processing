package com.vandunxg.file_processing.auth.api.dto.response;

import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

public record RegisterResponse(
    @Schema(description = "Newly created user id") UUID id,
    @Schema(description = "Login username") String username,
    @Schema(description = "Registered email address") String email,
    @Schema(description = "Human-readable display name") String displayName,
    @Schema(description = "Current account status", example = "PENDING_VERIFY") String status) {}
