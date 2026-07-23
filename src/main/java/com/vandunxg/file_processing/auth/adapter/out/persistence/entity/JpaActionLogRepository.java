package com.vandunxg.file_processing.auth.adapter.out.persistence.entity;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaActionLogRepository extends JpaRepository<ActionLogEntity, UUID> {}
