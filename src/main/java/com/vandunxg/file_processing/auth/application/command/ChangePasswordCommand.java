package com.vandunxg.file_processing.auth.application.command;

import java.util.UUID;

import lombok.Builder;

@Builder
public record ChangePasswordCommand(
    UUID userId,
    String currentPassword,
    String newPassword,
    String confirmPassword,
    String ipAddress) {}
