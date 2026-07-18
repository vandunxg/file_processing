package com.vandunxg.file_processing.auth.adapter.in.web.dto.request;

import com.vandunxg.common.models.dto.request.Request;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ResendVerificationRequest extends Request {

  @Schema(
      description = "Username or email identifying the pending account",
      example = "operator01@example.com")
  @NotBlank
  private String identifier;
}
