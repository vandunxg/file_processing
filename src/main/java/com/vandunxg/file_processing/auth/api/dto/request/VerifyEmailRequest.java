package com.vandunxg.file_processing.auth.api.dto.request;

import com.vandunxg.common.models.dto.request.Request;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class VerifyEmailRequest extends Request {

  @Schema(
      description = "Opaque email verification token carried by the verification link",
      example = "8f1e6c2a9b7d4e35f0a1c9d7e2b4f6a8")
  @NotBlank
  private String token;
}
