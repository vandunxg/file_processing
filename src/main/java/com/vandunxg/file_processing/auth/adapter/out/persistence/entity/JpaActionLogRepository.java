package com.vandunxg.file_processing.auth.adapter.out.persistence.entity;

import java.util.UUID;

import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.custom.JpaActionLogRepositoryCustom;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface JpaActionLogRepository
    extends JpaRepository<ActionLogEntity, UUID>, JpaActionLogRepositoryCustom {}
