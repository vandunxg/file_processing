package com.vandunxg.file_processing.auth.application.command;

import java.util.UUID;

import com.vandunxg.file_processing.auth.domain.model.RevocationReason;

public record RevokeAllSessionsCommand(UUID userId, RevocationReason reason, String ipAddress) {}
