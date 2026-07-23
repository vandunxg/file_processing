package com.vandunxg.file_processing.auth.adapter.in.web.dto.request;

import java.util.Set;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RoleRequest(
    @NotBlank @Pattern(regexp = "^[A-Za-z0-9_]+$") @Size(max = 50) String code,
    @NotBlank @Size(max = 100) String name,
    @Size(max = 1000) String description,
    Set<@Valid RolePermissionRequest> permissions) {

  public RoleRequest {
    permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
  }
}
