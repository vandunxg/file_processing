package com.vandunxg.file_processing.auth.infrastructure.persistence;

import java.util.List;

import com.vandunxg.file_processing.auth.application.capability.AuditLogSearchRepository;
import com.vandunxg.file_processing.auth.application.query.AuditLogSearchQuery;
import com.vandunxg.file_processing.auth.domain.AuditLogRepository;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import com.vandunxg.file_processing.auth.infrastructure.persistence.mapper.AuditLogPersistenceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-AUDIT-LOG-PERSISTENCE")
public class JpaAuditLogRepository implements AuditLogRepository, AuditLogSearchRepository {

  private final AuditLogEntityRepository auditLogEntityRepository;
  private final AuditLogPersistenceMapper auditLogPersistenceMapper;

  @Override
  public void record(AuditLog auditLog) {
    log.debug(
        "[record] persisting audit log domain={} operation={} objectId={}",
        auditLog.getDomain(),
        auditLog.getOperation(),
        auditLog.getObjectId());
    var saved = auditLogEntityRepository.save(auditLogPersistenceMapper.toEntity(auditLog));
    log.info("[record] persisted audit log id={}", saved.getId());
  }

  @Override
  public List<AuditLog> findAll() {
    return auditLogPersistenceMapper.toDomain(
        auditLogEntityRepository.findAllByOrderByChangedAtDesc());
  }

  @Override
  public Long count(AuditLogSearchQuery query) {
    return auditLogEntityRepository.count(query);
  }

  @Override
  public List<AuditLog> search(AuditLogSearchQuery query) {
    return auditLogPersistenceMapper.toDomain(auditLogEntityRepository.search(query));
  }
}
