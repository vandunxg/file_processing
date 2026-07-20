package com.vandunxg.file_processing.auth.application.port.out;

import java.util.List;

import com.vandunxg.file_processing.auth.domain.model.AuditLog;

public interface AuditLogPort {

  void record(AuditLog log);

  List<AuditLog> findAll();
}
