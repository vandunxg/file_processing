package com.vandunxg.file_processing.auth.application.service;

import java.util.List;

import com.vandunxg.common.models.dto.PageDTO;
import com.vandunxg.file_processing.auth.application.capability.AuditLogSearchRepository;
import com.vandunxg.file_processing.auth.application.query.AuditLogSearchQuery;
import com.vandunxg.file_processing.auth.domain.AuditLogRepository;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuditLogQueryService {

  private final AuditLogRepository auditLogRepository;
  private final AuditLogSearchRepository auditLogSearchRepository;

  @Transactional(readOnly = true)
  public List<AuditLog> list() {
    return auditLogRepository.findAll();
  }

  @Transactional(readOnly = true)
  public PageDTO<AuditLog> search(AuditLogSearchQuery query) {
    long count = auditLogSearchRepository.count(query);
    if (count == 0) {
      return PageDTO.of(List.of(), query.getPageIndex(), query.getPageSize(), 0);
    }
    return PageDTO.of(
        auditLogSearchRepository.search(query), query.getPageIndex(), query.getPageSize(), count);
  }
}
