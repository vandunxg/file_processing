package com.vandunxg.file_processing.auth.api.dto.request;

import com.vandunxg.common.models.dto.request.Request;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ResetPasswordRequest extends Request {

  @Schema(description = "Opaque password reset token", format = "password")
  @NotBlank
  private String token;

  @Schema(description = "New account password", format = "password")
  @NotBlank
  private String newPassword;

  @Schema(description = "Confirmation of the new account password", format = "password")
  @NotBlank
  private String confirmPassword;
}
