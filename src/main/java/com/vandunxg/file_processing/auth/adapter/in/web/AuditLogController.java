package com.vandunxg.file_processing.auth.adapter.in.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.vandunxg.common.models.dto.response.Response;
import com.vandunxg.file_processing.auth.application.service.AuditReadService;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${app.api.prefix}/${app.api.version}/admin/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

  private final AuditReadService auditReadService;

  @Operation(summary = "Read security audit logs")
  @GetMapping
  @PreAuthorize("hasPermission(null, 'audit:read')")
  public Response<List<AuditLogResponse>> list() {
    return Response.of(auditReadService.list().stream().map(AuditLogResponse::from).toList());
  }

  public record AuditLogResponse(
      UUID id,
      String domain,
      UUID objectId,
      String operation,
      UUID changedBy,
      Instant changedAt,
      Map<String, Object> data) {
    private static AuditLogResponse from(AuditLog audit) {
      return new AuditLogResponse(
          audit.getId(),
          audit.getDomain().name(),
          audit.getObjectId(),
          audit.getOperation().name(),
          audit.getChangedBy(),
          audit.getChangedAt(),
          audit.getData());
    }
  }
}
