package com.vandunxg.file_processing.auth.application.command;

import lombok.Builder;

@Builder
public record RefreshTokenCommand(String refreshToken, String userAgent, String ipAddress) {}
