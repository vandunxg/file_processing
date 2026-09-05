package com.vandunxg.file_processing.auth.application.command;

import java.util.UUID;

import lombok.Builder;

@Builder
public record RevokeSessionCommand(
    UUID sessionId, UUID callerUserId, UUID callerSessionId, String ipAddress) {}
