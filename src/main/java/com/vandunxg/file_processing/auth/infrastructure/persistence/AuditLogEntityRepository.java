package com.vandunxg.file_processing.auth.infrastructure.persistence;

import java.util.List;
import java.util.UUID;

import com.vandunxg.file_processing.auth.infrastructure.persistence.entity.AuditLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AuditLogEntityRepository
    extends JpaRepository<AuditLogEntity, UUID>, AuditLogEntityRepositoryCustom {

  List<AuditLogEntity> findAllByOrderByChangedAtDesc();
}
