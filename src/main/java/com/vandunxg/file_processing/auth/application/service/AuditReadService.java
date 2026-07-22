package com.vandunxg.file_processing.auth.application.service;

import com.vandunxg.common.models.dto.PageDTO;
import com.vandunxg.file_processing.auth.application.port.out.AuditLogPort;
import com.vandunxg.file_processing.auth.application.query.AuditLogSearchQuery;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AuditReadService {

  private final AuditLogPort auditLogPort;

  @Transactional(readOnly = true)
  public List<AuditLog> list() {
    return auditLogPort.findAll();
  }

  @Transactional(readOnly = true)
  public PageDTO<AuditLog> search(AuditLogSearchQuery query) {
    long count = auditLogPort.count(query);
    if (count == 0) {
      return PageDTO.of(List.of(), query.getPageIndex(), query.getPageSize(), 0);
    }
    return PageDTO.of(auditLogPort.search(query), query.getPageIndex(), query.getPageSize(), count);
  }
}
