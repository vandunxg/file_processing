package com.vandunxg.file_processing.auth.adapter.out.persistence.entity.custom;

import java.util.List;

import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.AuditLogEntity;
import com.vandunxg.file_processing.auth.application.query.AuditLogSearchQuery;

public interface JpaAuditLogRepositoryCustom {
  Long count(AuditLogSearchQuery query);

  List<AuditLogEntity> search(AuditLogSearchQuery query);
}
