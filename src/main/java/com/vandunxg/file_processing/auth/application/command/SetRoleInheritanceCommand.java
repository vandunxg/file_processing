package com.vandunxg.file_processing.auth.application.command;

import java.util.UUID;

import lombok.Builder;

/** Three interchangeable UUIDs: the builder is what stops a silent transposition. */
@Builder
public record SetRoleInheritanceCommand(UUID actorId, UUID roleId, UUID roleInheritedId) {}
