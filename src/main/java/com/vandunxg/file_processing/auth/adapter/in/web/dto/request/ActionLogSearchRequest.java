package com.vandunxg.file_processing.auth.adapter.in.web.dto.request;

import java.time.Instant;

import com.vandunxg.common.models.dto.request.PagingRequest;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import org.springframework.format.annotation.DateTimeFormat;

@Getter
@Setter
@EqualsAndHashCode(callSuper = true)
public class ActionLogSearchRequest extends PagingRequest {

  @Schema(description = "Case-insensitive username filter", example = "operator")
  private String username;

  @Schema(description = "Case-insensitive API documentation filter", example = "processing")
  private String apiDoc;

  @Schema(description = "Case-insensitive error content filter", example = "database")
  private String errorMessage;

  @Schema(description = "Exact HTTP request method filter", example = "GET")
  private String requestMethod;

  @Schema(
      description = "Inclusive request start-time lower bound",
      example = "2026-07-27T00:00:00Z")
  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
  private Instant startTimeFrom;

  @Schema(
      description = "Inclusive request start-time upper bound",
      example = "2026-07-28T00:00:00Z")
  @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
  private Instant startTimeTo;
}
