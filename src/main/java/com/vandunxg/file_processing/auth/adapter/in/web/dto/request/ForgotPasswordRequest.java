package com.vandunxg.file_processing.auth.adapter.in.web.dto.request;

import com.vandunxg.common.models.dto.request.Request;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ForgotPasswordRequest extends Request {

  @Schema(description = "Username or email for the account", example = "operator01@example.com")
  @NotBlank
  @Size(min = 3, max = 254)
  private String identifier;
}
