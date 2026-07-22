package com.vandunxg.file_processing.auth.adapter.in.web.dto.request;

import com.vandunxg.common.models.dto.request.PagingRequest;
import com.vandunxg.file_processing.auth.domain.model.ActiveStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.*;

@Setter
@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = true)
public class RoleSearchRequest extends PagingRequest {

  @Schema(description = "Filter by role status", example = "ACTIVE")
  private ActiveStatus status;
}
