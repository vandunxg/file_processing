package com.vandunxg.file_processing.auth.domain.event;

import java.time.Instant;
import java.util.UUID;

import com.vandunxg.file_processing.auth.domain.model.RevocationReason;

public record SessionPersistEvent(
    UUID id,
    UUID userId,
    int credentialVersion,
    String refreshTokenHash,
    String userAgent,
    String ipAddressHash,
    Instant createdAt,
    Instant lastUsedAt,
    Instant expiresAt,
    Instant revokedAt,
    RevocationReason revokedReason) {}
