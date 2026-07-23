package com.vandunxg.file_processing.auth.application.command;

import java.util.UUID;

public record SetRoleInheritanceCommand(UUID actorId, UUID roleId, UUID roleInheritedId) {}
