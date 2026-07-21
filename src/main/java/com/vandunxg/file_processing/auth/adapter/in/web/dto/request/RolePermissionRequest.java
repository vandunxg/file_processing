package com.vandunxg.file_processing.auth.adapter.in.web.dto.request;

import java.util.Set;

import com.vandunxg.common.models.enums.Action;
import com.vandunxg.file_processing.auth.domain.model.ResourceCode;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

public record RolePermissionRequest(
    @NotNull ResourceCode resourceCode, @NotEmpty Set<Action> actions) {}
