package com.vandunxg.file_processing.auth.adapter.out.persistence.entity;

import java.time.Instant;
import java.util.UUID;

import com.vandunxg.common.models.entities.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.*;

@Entity
@Table(name = "action_logs")
@Getter
@Setter
@ToString
@RequiredArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false, of = "id")
public class ActionLogEntity extends AuditableEntity {

  @Id
  @Column(name = "id")
  private UUID id;

  @Column(name = "user_id")
  private UUID userId;

  @Column(name = "username", length = 100)
  private String username;

  @Column(name = "start_time", nullable = false)
  private Instant startTime;

  @Column(name = "end_time", nullable = false)
  private Instant endTime;

  @Column(name = "duration", nullable = false)
  private Long duration;

  @Column(name = "path", nullable = false, length = 500)
  private String path;

  @Column(name = "api_doc", length = 500)
  private String apiDoc;

  @Column(name = "request_method", nullable = false, length = 20)
  private String requestMethod;

  @Column(name = "ip_address", length = 64)
  private String ipAddress;

  @Column(name = "user_agent", length = 512)
  private String userAgent;

  @Column(name = "request_data", columnDefinition = "TEXT")
  private String requestData;

  @Column(name = "status_code", nullable = false)
  private Integer statusCode;

  @Column(name = "error_message", columnDefinition = "TEXT")
  private String errorMessage;

  @Column(name = "request_param", columnDefinition = "TEXT")
  private String requestParam;

  @Column(name = "deleted_at")
  private Instant deletedAt;
}
