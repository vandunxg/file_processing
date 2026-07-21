package com.vandunxg.file_processing.auth.application.command;

import java.util.Set;

import com.vandunxg.common.models.enums.Action;
import com.vandunxg.file_processing.auth.domain.model.ResourceCode;

public record RolePermissionCommand(ResourceCode resourceCode, Set<Action> actions) {

  public RolePermissionCommand {
    actions = actions == null ? Set.of() : Set.copyOf(actions);
  }
}
