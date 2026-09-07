package com.vandunxg.file_processing.auth.api.dto.response;

import java.util.List;
import java.util.UUID;

import io.swagger.v3.oas.annotations.media.Schema;

public record MeResponse(
    @Schema(description = "Authenticated user id") UUID userId,
    @Schema(description = "Login username") String username,
    @Schema(description = "Registered email address") String email,
    @Schema(description = "Human-readable display name") String displayName,
    @Schema(description = "Role codes attached to the user") List<String> roles,
    @Schema(description = "Account status", example = "ACTIVE") String status) {}
