package com.vandunxg.file_processing.auth.application.service;

import java.util.List;

import com.vandunxg.file_processing.auth.application.port.out.AuditLogPort;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuditReadService {

  private final AuditLogPort auditLogPort;

  @Transactional(readOnly = true)
  public List<AuditLog> list() {
    return auditLogPort.findAll();
  }
}
