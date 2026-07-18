package com.vandunxg.file_processing.auth.adapter.in.web;

import com.vandunxg.common.models.dto.response.Response;
import com.vandunxg.common.web.support.IpUtils;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.request.RegisterRequest;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.request.ResendVerificationRequest;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.request.VerifyEmailRequest;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.response.RegisterResponse;
import com.vandunxg.file_processing.auth.adapter.in.web.mapper.AuthWebMapper;
import com.vandunxg.file_processing.auth.application.port.in.RegisterUseCase;
import com.vandunxg.file_processing.auth.application.port.in.ResendVerificationEmailUseCase;
import com.vandunxg.file_processing.auth.application.port.in.VerifyEmailUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${app.api.prefix}/${app.api.version}/auth")
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-CONTROLLER")
public class AuthController {

  private final RegisterUseCase registerUseCase;
  private final VerifyEmailUseCase verifyEmailUseCase;
  private final ResendVerificationEmailUseCase resendVerificationEmailUseCase;
  private final AuthWebMapper webMapper;

  @Operation(summary = "Register a new operator account")
  @SecurityRequirements
  @PostMapping("/register")
  @ResponseStatus(HttpStatus.CREATED)
  public Response<RegisterResponse> register(
      @Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
    String clientIp = clientIp(http);
    log.info("[register] username={} ip={}", request.getUsername(), clientIp);
    var command = webMapper.toCommand(request, clientIp);
    var result = registerUseCase.register(command);
    return Response.of(webMapper.toResponse(result));
  }

  @Operation(summary = "Verify an email address using an opaque token")
  @SecurityRequirements
  @PostMapping("/verify-email")
  public Response<RegisterResponse> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
    var result = verifyEmailUseCase.verifyEmail(webMapper.toCommand(request));
    return Response.of(webMapper.toResponse(result));
  }

  @Operation(summary = "Resend a verification email (enumeration-safe)")
  @SecurityRequirements
  @PostMapping("/resend-verification")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void resendVerification(
      @Valid @RequestBody ResendVerificationRequest request, HttpServletRequest http) {
    resendVerificationEmailUseCase.resend(webMapper.toCommand(request, clientIp(http)));
  }

  private String clientIp(HttpServletRequest http) {
    return IpUtils.getRemoteIp(http);
  }
}
