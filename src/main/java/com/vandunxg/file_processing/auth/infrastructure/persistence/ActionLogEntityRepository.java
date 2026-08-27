package com.vandunxg.file_processing.auth.infrastructure.persistence;

import java.util.UUID;

import com.vandunxg.file_processing.auth.infrastructure.persistence.entity.ActionLogEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ActionLogEntityRepository
    extends JpaRepository<ActionLogEntity, UUID>, ActionLogEntityRepositoryCustom {}
