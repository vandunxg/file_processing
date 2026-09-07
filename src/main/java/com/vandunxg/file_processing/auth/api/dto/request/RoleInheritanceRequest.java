package com.vandunxg.file_processing.auth.api.dto.request;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record RoleInheritanceRequest(@NotNull UUID roleId, UUID roleInheritedId) {}
