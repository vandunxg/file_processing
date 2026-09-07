package com.vandunxg.file_processing.auth.infrastructure.persistence;

import java.util.List;

import com.vandunxg.file_processing.auth.application.query.AuditLogSearchQuery;
import com.vandunxg.file_processing.auth.infrastructure.persistence.entity.AuditLogEntity;

public interface AuditLogEntityRepositoryCustom {
  Long count(AuditLogSearchQuery query);

  List<AuditLogEntity> search(AuditLogSearchQuery query);
}
