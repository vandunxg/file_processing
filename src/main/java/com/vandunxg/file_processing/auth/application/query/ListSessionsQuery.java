package com.vandunxg.file_processing.auth.application.query;

import java.util.UUID;

import lombok.Builder;

/** Both fields are UUIDs, so the builder is here to prevent transposing them. */
@Builder
public record ListSessionsQuery(UUID userId, UUID currentSessionId) {}
