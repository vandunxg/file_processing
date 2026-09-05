package com.vandunxg.file_processing.auth.application.result;

import java.util.UUID;

import com.vandunxg.file_processing.auth.domain.model.UserStatus;

public record RegisterResult(
    UUID id, String username, String email, String displayName, UserStatus status) {}
