package com.vandunxg.file_processing.auth.adapter.in.web.dto.request;

import com.vandunxg.common.models.dto.request.PagingRequest;
import com.vandunxg.file_processing.auth.domain.model.ActiveStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Pattern;
import lombok.*;

@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class RoleSearchRequest extends PagingRequest {

  private static final String ROLE_SORT_PATTERN =
      "^(?:(?:code|name|status|createdAt)\\.(?:asc|desc))(?:,(?:code|name|status|createdAt)\\.(?:asc|desc))*$";

  @Schema(description = "Filter by role status", example = "ACTIVE")
  private ActiveStatus status;

  @Override
  @Schema(description = "Role sort fields", example = "code.asc")
  @Pattern(regexp = ROLE_SORT_PATTERN, message = "ROLE_SORT_INVALID")
  public String getSortBy() {
    return super.getSortBy();
  }
}
