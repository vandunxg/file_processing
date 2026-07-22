package com.vandunxg.file_processing.auth.adapter.in.web.dto.request;

import java.util.UUID;

import com.vandunxg.common.models.dto.request.PagingRequest;
import com.vandunxg.file_processing.auth.domain.model.AuditLogDomain;
import com.vandunxg.file_processing.auth.domain.model.OperationType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
@EqualsAndHashCode(callSuper = true)
public class AuditLogSearchRequest extends PagingRequest {

  @Schema(description = "Filter by audit domain", example = "AUTH")
  private AuditLogDomain domain;

  @Schema(description = "Filter by operation", example = "LOGIN_SUCCEEDED")
  private OperationType operation;

  @Schema(description = "Filter by actor id")
  private UUID changedBy;
}
