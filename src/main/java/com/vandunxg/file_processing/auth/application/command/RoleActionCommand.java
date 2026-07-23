package com.vandunxg.file_processing.auth.application.command;

import java.util.UUID;

public record RoleActionCommand(UUID actorId, UUID roleId) {}
