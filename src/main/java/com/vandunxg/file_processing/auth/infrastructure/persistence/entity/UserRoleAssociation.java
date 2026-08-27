package com.vandunxg.file_processing.auth.infrastructure.persistence.entity;

import java.util.UUID;

public record UserRoleAssociation(UUID userId, RoleEntity role) {}
