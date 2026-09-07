package com.vandunxg.file_processing.auth.application.command;

import java.util.UUID;

import lombok.Builder;

@Builder
public record LogoutCommand(UUID sessionId, UUID userId, String ipAddress) {}
