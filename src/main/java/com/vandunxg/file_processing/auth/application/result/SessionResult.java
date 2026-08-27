package com.vandunxg.file_processing.auth.application.result;

import java.time.Instant;
import java.util.UUID;

public record SessionResult(
    UUID sessionId,
    String userAgent,
    Instant createdAt,
    Instant lastUsedAt,
    Instant expiresAt,
    boolean current) {}
