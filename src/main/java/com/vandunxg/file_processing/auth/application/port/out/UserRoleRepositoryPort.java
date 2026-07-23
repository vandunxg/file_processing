package com.vandunxg.file_processing.auth.application.port.out;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import com.vandunxg.file_processing.auth.domain.model.UserRole;

public interface UserRoleRepositoryPort {

  UserRole save(UserRole userRole);

  void replaceRoles(UUID userId, Set<UUID> roleIds, Instant now);
}
