package com.vandunxg.file_processing.auth.adapter.in.web.dto.request;

import com.vandunxg.common.models.dto.request.Request;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChangePasswordRequest extends Request {

  @Schema(description = "Current account password", format = "password")
  @NotBlank
  private String currentPassword;

  @Schema(description = "New account password", format = "password")
  @NotBlank
  private String newPassword;

  @Schema(description = "Confirmation of the new account password", format = "password")
  @NotBlank
  private String confirmPassword;
}
