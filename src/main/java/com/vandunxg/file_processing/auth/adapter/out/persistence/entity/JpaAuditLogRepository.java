package com.vandunxg.file_processing.auth.adapter.out.persistence.entity;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface JpaAuditLogRepository extends JpaRepository<AuditLogEntity, UUID> {

  List<AuditLogEntity> findAllByOrderByChangedAtDesc();
}
