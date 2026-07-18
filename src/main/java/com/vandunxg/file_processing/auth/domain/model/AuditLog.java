package com.vandunxg.file_processing.auth.domain.model;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.vandunxg.common.models.domain.AuditableDomain;
import lombok.*;
import lombok.experimental.SuperBuilder;

@Getter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Setter(AccessLevel.PRIVATE)
@EqualsAndHashCode(callSuper = false)
public class AuditLog extends AuditableDomain {

  private UUID id;
  private AuditLogDomain domain;
  private UUID objectId;
  private OperationType operation;
  private UUID changedBy;
  private Instant changedAt;
  private Map<String, Object> data;
  private String ipAddress;
  private String browser;
  private String userAgent;
  private Instant deletedAt;
}
