package com.vandunxg.file_processing.auth.domain.event;

import java.time.Instant;
import java.util.UUID;

public record SessionRotationEvent(
    UUID sessionId, String newRefreshTokenHash, Instant lastUsedAt) {}
