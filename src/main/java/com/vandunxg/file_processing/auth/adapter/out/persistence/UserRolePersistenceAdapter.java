package com.vandunxg.file_processing.auth.adapter.out.persistence;

import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.JpaUserRoleRepository;
import com.vandunxg.file_processing.auth.adapter.out.persistence.mapper.UserRolePersistenceMapper;
import com.vandunxg.file_processing.auth.application.port.out.UserRoleRepositoryPort;
import com.vandunxg.file_processing.auth.domain.model.UserRole;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-USER-ROLE-PERSISTENCE")
public class UserRolePersistenceAdapter implements UserRoleRepositoryPort {

  private final JpaUserRoleRepository jpaUserRoleRepository;
  private final UserRolePersistenceMapper userRolePersistenceMapper;

  @Override
  public UserRole save(UserRole userRole) {
    log.debug(
        "[save] persisting user_role userId={} roleId={}",
        userRole.getUserId(),
        userRole.getRoleId());
    var saved = jpaUserRoleRepository.save(userRolePersistenceMapper.toEntity(userRole));
    log.info("[save] persisted user_role id={}", saved.getId());
    return userRolePersistenceMapper.toDomain(saved);
  }
}
