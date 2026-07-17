package com.vandunxg.file_processing.auth.adapter.out.persistence;

import java.util.Optional;

import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.JpaRoleRepository;
import com.vandunxg.file_processing.auth.adapter.out.persistence.mapper.RolePersistenceMapper;
import com.vandunxg.file_processing.auth.application.port.out.RoleRepositoryPort;
import com.vandunxg.file_processing.auth.domain.model.Role;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-ROLE-PERSISTENCE")
public class RolePersistenceAdapter implements RoleRepositoryPort {

  private final JpaRoleRepository jpaRoleRepository;
  private final RolePersistenceMapper rolePersistenceMapper;

  @Override
  public Optional<Role> findByCode(String code) {
    return jpaRoleRepository
        .findByCodeAndDeletedAtIsNull(code)
        .map(rolePersistenceMapper::toDomain);
  }
}
