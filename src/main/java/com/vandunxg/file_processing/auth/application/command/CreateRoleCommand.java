package com.vandunxg.file_processing.auth.application.command;

import java.util.Set;
import java.util.UUID;

public record CreateRoleCommand(
    UUID actorId,
    String code,
    String name,
    String description,
    Set<RolePermissionCommand> permissions) {

  public CreateRoleCommand {
    permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
  }
}
