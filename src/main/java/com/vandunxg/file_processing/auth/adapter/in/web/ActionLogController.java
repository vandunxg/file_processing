package com.vandunxg.file_processing.auth.adapter.in.web;

import java.beans.PropertyEditorSupport;
import java.time.Instant;
import java.util.UUID;

import com.vandunxg.common.models.dto.PageDTO;
import com.vandunxg.common.models.dto.response.PagingResponse;
import com.vandunxg.common.models.validator.ValidatePaging;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.request.ActionLogSearchRequest;
import com.vandunxg.file_processing.auth.adapter.in.web.mapper.ActionLogWebMapper;
import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.ActionLogEntity;
import com.vandunxg.file_processing.auth.application.query.ActionLogSearchQuery;
import com.vandunxg.file_processing.auth.application.service.ActionLogReadService;
import com.vandunxg.file_processing.auth.domain.model.ActionLog;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.InitBinder;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${app.api.prefix}/${app.api.version}/admin/action-logs")
@RequiredArgsConstructor
@Tag(
    name = "Admin action logs",
    description = "Bearer access token required. `all:manage` satisfies `action_log:read`.")
public class ActionLogController {

  private final ActionLogReadService actionLogReadService;
  private final ActionLogWebMapper actionLogWebMapper;

  @InitBinder
  void bindInstant(WebDataBinder binder) {
    binder.registerCustomEditor(
        Instant.class,
        new PropertyEditorSupport() {
          @Override
          public void setAsText(String text) {
            setValue(Instant.parse(text));
          }
        });
  }

  @Operation(
      summary = "Read failed HTTP action logs",
      description =
          "Requires `action_log:read`. Response includes raw request and error content by approved exception.")
  @GetMapping
  @PreAuthorize("hasPermission(null, 'action_log:read')")
  public PagingResponse<ActionLogResponse> search(
      @ValidatePaging(sortModel = ActionLogEntity.class) ActionLogSearchRequest request) {
    ActionLogSearchQuery searchQuery = actionLogWebMapper.toQuery(request);
    PageDTO<ActionLog> resultPage = actionLogReadService.search(searchQuery);
    return new PagingResponse<>(resultPage, actionLogWebMapper::toResponse);
  }

  public record ActionLogResponse(
      UUID id,
      UUID userId,
      String username,
      Instant startTime,
      Instant endTime,
      Long duration,
      String path,
      String apiDoc,
      String requestMethod,
      String ipAddress,
      String userAgent,
      String requestData,
      Integer statusCode,
      String errorMessage,
      String requestParam) {}
}
