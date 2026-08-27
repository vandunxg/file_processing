package com.vandunxg.file_processing.auth.application.capability;

import java.util.List;

import com.vandunxg.file_processing.auth.application.query.AuditLogSearchQuery;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;

/** Paginated audit-log read model. */
public interface AuditLogSearchRepository {

  Long count(AuditLogSearchQuery query);

  List<AuditLog> search(AuditLogSearchQuery query);
}
