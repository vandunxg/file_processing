package com.vandunxg.file_processing.auth.domain.model;

import java.time.Instant;
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
public class ActionLog extends AuditableDomain {
  private UUID id;
  private UUID userId;
  private String username;
  private Instant startTime;
  private Instant endTime;
  private Long duration;
  private String path;
  private String apiDoc;
  private String requestMethod;
  private String ipAddress;
  private String userAgent;
  private String requestData;
  private Integer statusCode;
  private String errorMessage;
  private String requestParam;
  private Instant deletedAt;
}
