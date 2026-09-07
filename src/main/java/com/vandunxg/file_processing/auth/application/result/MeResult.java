package com.vandunxg.file_processing.auth.application.result;

import java.util.List;
import java.util.UUID;

import com.vandunxg.file_processing.auth.domain.model.UserStatus;

public record MeResult(
    UUID userId,
    String username,
    String email,
    String displayName,
    List<String> roles,
    UserStatus status) {}
