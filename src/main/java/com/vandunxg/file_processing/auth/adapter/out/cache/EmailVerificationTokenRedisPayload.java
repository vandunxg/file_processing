package com.vandunxg.file_processing.auth.adapter.out.cache;

import java.time.Instant;
import java.util.UUID;

record EmailVerificationTokenRedisPayload(
    UUID id, UUID userId, Instant issuedAt, Instant expiresAt, String ipAddressHash) {}
