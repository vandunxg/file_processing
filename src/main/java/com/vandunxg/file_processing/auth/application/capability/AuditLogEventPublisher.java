package com.vandunxg.file_processing.auth.application.capability;

import com.vandunxg.file_processing.auth.domain.model.AuditLog;

public interface AuditLogEventPublisher {

  void publish(AuditLog auditLog);
}
