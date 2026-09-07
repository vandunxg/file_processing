package com.vandunxg.file_processing.auth.application.command;

import lombok.Builder;

@Builder
public record RegisterCommand(
    String username, String email, String displayName, String password, String ipAddress) {}
