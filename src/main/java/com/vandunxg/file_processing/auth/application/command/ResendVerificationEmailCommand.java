package com.vandunxg.file_processing.auth.application.command;

import lombok.Builder;

/** Both fields are strings, so the builder is here to prevent transposing them. */
@Builder
public record ResendVerificationEmailCommand(String identifier, String ipAddress) {}
