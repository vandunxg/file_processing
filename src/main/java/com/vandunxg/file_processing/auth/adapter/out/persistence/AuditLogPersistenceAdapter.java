package com.vandunxg.file_processing.auth.adapter.out.persistence;

import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.JpaAuditLogRepository;
import com.vandunxg.file_processing.auth.adapter.out.persistence.mapper.AuditLogPersistenceMapper;
import com.vandunxg.file_processing.auth.application.port.out.AuditLogPort;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-AUDIT-LOG-PERSISTENCE")
public class AuditLogPersistenceAdapter implements AuditLogPort {

  private final JpaAuditLogRepository jpaAuditLogRepository;
  private final AuditLogPersistenceMapper auditLogPersistenceMapper;

  @Override
  public void record(AuditLog auditLog) {
    log.debug(
        "[record] persisting audit log domain={} operation={} objectId={}",
        auditLog.getDomain(),
        auditLog.getOperation(),
        auditLog.getObjectId());
    var saved = jpaAuditLogRepository.save(auditLogPersistenceMapper.toEntity(auditLog));
    log.info("[record] persisted audit log id={}", saved.getId());
  }
}
