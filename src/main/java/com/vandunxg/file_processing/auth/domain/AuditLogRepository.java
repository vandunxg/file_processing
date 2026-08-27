package com.vandunxg.file_processing.auth.domain;

import java.util.List;

import com.vandunxg.file_processing.auth.domain.model.AuditLog;

/** Append-only store for {@link AuditLog}. Paginated reads live in the application layer. */
public interface AuditLogRepository {

  void record(AuditLog log);

  List<AuditLog> findAll();
}
