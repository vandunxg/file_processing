package com.vandunxg.file_processing.auth.api.dto.request;

import com.vandunxg.common.models.dto.request.PagingRequest;
import com.vandunxg.file_processing.auth.domain.model.UserStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

@Setter
@Getter
@EqualsAndHashCode(callSuper = true)
public class UserSearchRequest extends PagingRequest {

  @Schema(description = "Filter by user status", example = "ACTIVE")
  private UserStatus status;
}
