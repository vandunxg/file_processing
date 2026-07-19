package com.vandunxg.file_processing.auth.adapter.in.web.dto.request;

import com.vandunxg.common.models.dto.request.Request;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class LoginRequest extends Request {

  @Schema(
      description = "Username or email used at registration",
      example = "operator01",
      minLength = 3,
      maxLength = 254)
  @NotBlank
  @Size(min = 3, max = 254)
  private String username;

  @Schema(description = "Raw account password", example = "StrongPassw0rd!", format = "password")
  @NotBlank
  private String password;
}
