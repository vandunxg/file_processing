package com.vandunxg.file_processing.auth.adapter.in.web.dto.request;

import com.vandunxg.common.models.dto.request.Request;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class RegisterRequest extends Request {

  @Schema(
      description = "Unique login username",
      example = "operator01",
      minLength = 3,
      maxLength = 64)
  @NotBlank
  @Size(min = 3, max = 64)
  private String username;

  @Schema(
      description = "Operator email address, used for login and verification",
      example = "operator01@example.com",
      maxLength = 254)
  @NotBlank
  @Email
  @Size(max = 254)
  private String email;

  @Schema(
      description = "Human-readable display name",
      example = "Nguyen Van A",
      minLength = 2,
      maxLength = 150)
  @NotBlank
  @Size(min = 2, max = 150)
  private String displayName;

  @Schema(
      description =
          "Account password; length/character policy is enforced server-side and returns 400 on"
              + " violation",
      example = "StrongPassw0rd!",
      format = "password")
  @NotBlank
  private String password;
}
