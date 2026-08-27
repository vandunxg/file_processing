package com.vandunxg.file_processing.auth.infrastructure.persistence.entity;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.vandunxg.common.models.entities.AuditableEntity;
import com.vandunxg.file_processing.auth.domain.model.AuditLogDomain;
import com.vandunxg.file_processing.auth.domain.model.OperationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.ToString;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "audit_logs")
@Getter
@Setter
@ToString
@RequiredArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false, of = "id")
public class AuditLogEntity extends AuditableEntity {

  @Id
  @Column(name = "id")
  private UUID id;

  @Enumerated(EnumType.STRING)
  @Column(name = "domain", nullable = false, length = 50)
  private AuditLogDomain domain;

  @Column(name = "object_id")
  private UUID objectId;

  @Enumerated(EnumType.STRING)
  @Column(name = "operation", nullable = false, length = 50)
  private OperationType operation;

  @Column(name = "changed_by")
  private UUID changedBy;

  @Column(name = "changed_at", nullable = false)
  private Instant changedAt;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "data", columnDefinition = "jsonb")
  private Map<String, Object> data;

  @Column(name = "ip_address", length = 64)
  private String ipAddress;

  @Column(name = "browser", length = 64)
  private String browser;

  @Column(name = "user_agent", length = 200)
  private String userAgent;

  @Column(name = "deleted_at")
  private Instant deletedAt;
}
