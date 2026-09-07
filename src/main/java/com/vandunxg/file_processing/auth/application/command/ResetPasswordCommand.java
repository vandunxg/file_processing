package com.vandunxg.file_processing.auth.application.command;

import lombok.Builder;

@Builder
public record ResetPasswordCommand(
    String token, String newPassword, String confirmPassword, String ipAddress) {}
