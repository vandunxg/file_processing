package com.vandunxg.file_processing.auth.adapter.in.web.dto.request;

import java.util.UUID;

import jakarta.validation.constraints.NotNull;

public record RoleInheritanceRequest(@NotNull UUID roleId, UUID roleInheritedId) {}
