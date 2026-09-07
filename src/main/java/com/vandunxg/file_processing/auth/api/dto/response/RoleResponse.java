package com.vandunxg.file_processing.auth.api.dto.response;

import java.util.Set;
import java.util.UUID;

public record RoleResponse(
    UUID id,
    String code,
    String name,
    String description,
    boolean isConst,
    String status,
    UUID roleInheritedId,
    Set<String> permissions) {}
