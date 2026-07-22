package com.vandunxg.file_processing.auth.adapter.in.web;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import com.vandunxg.common.models.dto.PageDTO;
import com.vandunxg.common.models.dto.response.PagingResponse;
import com.vandunxg.common.models.validator.ValidatePaging;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.request.AuditLogSearchRequest;
import com.vandunxg.file_processing.auth.adapter.in.web.mapper.AuditLogWebMapper;
import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.AuditLogEntity;
import com.vandunxg.file_processing.auth.application.query.AuditLogSearchQuery;
import com.vandunxg.file_processing.auth.application.service.AuditReadService;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${app.api.prefix}/${app.api.version}/admin/audit-logs")
@RequiredArgsConstructor
@Tag(
    name = "Admin audit logs",
    description = "Bearer access token required. `all:manage` satisfies `audit:read`.")
public class AuditLogController {

  private final AuditReadService auditReadService;
  private final AuditLogWebMapper auditLogWebMapper;

  @Operation(summary = "Read security audit logs", description = "Requires `audit:read`.")
  @GetMapping
  @PreAuthorize("hasPermission(null, 'audit:read')")
  public PagingResponse<AuditLogResponse> list(
      @ValidatePaging(sortModel = AuditLogEntity.class) AuditLogSearchRequest request) {

    AuditLogSearchQuery searchQuery = auditLogWebMapper.toQuery(request);
    PageDTO<AuditLog> resultPage = auditReadService.search(searchQuery);

    return new PagingResponse<>(resultPage, auditLogWebMapper::toResponse);
  }

  public record AuditLogResponse(
      UUID id,
      String domain,
      UUID objectId,
      String operation,
      UUID changedBy,
      Instant changedAt,
      Map<String, Object> data) {}
}
