package com.vandunxg.file_processing.auth.adapter.in.web.dto.request;

import com.vandunxg.common.models.dto.request.Request;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RefreshTokenRequest extends Request {

  @Schema(
      description = "Opaque refresh token previously issued by /auth/login or /auth/refresh",
      example = "aVjF3-4KpQ9x…",
      format = "password")
  @NotBlank
  private String refreshToken;
}
