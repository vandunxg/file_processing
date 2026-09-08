package com.vandunxg.file_processing.fileimport.application.service;

import java.time.Instant;
import java.util.UUID;

import com.vandunxg.file_processing.auth.application.AuditTrail;
import com.vandunxg.file_processing.auth.domain.model.AuditLogDomain;
import com.vandunxg.file_processing.auth.domain.model.OperationType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Writes the file-import audit vocabulary through the owning audit application service. */
@Service
@RequiredArgsConstructor
public class FileImportAuditService {

  private final AuditTrail auditTrail;

  public void record(OperationType operation, UUID resourceId, UUID actorId, Instant occurredAt) {
    auditTrail.recordAfterCommit(
        AuditTrail.entry(AuditLogDomain.FILE_IMPORT, resourceId, operation, actorId, occurredAt)
            .build());
  }
}
