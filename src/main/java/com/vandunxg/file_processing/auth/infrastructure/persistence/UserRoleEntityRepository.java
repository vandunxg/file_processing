package com.vandunxg.file_processing.auth.infrastructure.persistence;

import java.util.List;
import java.util.UUID;

import com.vandunxg.file_processing.auth.infrastructure.persistence.entity.UserRoleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface UserRoleEntityRepository extends JpaRepository<UserRoleEntity, UUID> {

  List<UserRoleEntity> findByUserIdAndDeletedAtIsNull(UUID userId);
}
