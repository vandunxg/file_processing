package com.vandunxg.file_processing.auth.application.command;

import java.util.Set;
import java.util.UUID;

public record UpdateRoleCommand(
    UUID actorId,
    UUID roleId,
    String code,
    String name,
    String description,
    Set<RolePermissionCommand> permissions) {

  public UpdateRoleCommand {
    permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
  }
}
