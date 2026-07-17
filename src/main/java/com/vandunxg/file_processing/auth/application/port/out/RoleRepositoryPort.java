package com.vandunxg.file_processing.auth.application.port.out;

import java.util.Optional;

import com.vandunxg.file_processing.auth.domain.model.Role;

public interface RoleRepositoryPort {

  /** Active only: deleted_at IS NULL. */
  Optional<Role> findByCode(String code);
}
