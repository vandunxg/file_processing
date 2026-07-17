package com.vandunxg.file_processing.auth.application.port.out;

import com.vandunxg.file_processing.auth.domain.model.UserRole;

public interface UserRoleRepositoryPort {

  UserRole save(UserRole userRole);
}
