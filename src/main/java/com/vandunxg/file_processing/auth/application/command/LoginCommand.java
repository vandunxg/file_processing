package com.vandunxg.file_processing.auth.application.command;

import lombok.Builder;

@Builder
public record LoginCommand(String username, String password, String userAgent, String ipAddress) {}
