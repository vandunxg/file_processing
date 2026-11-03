package com.vandunxg.file_processing.auth.api;

import java.util.UUID;

import com.vandunxg.common.models.dto.response.Response;
import com.vandunxg.common.web.support.IpUtils;
import com.vandunxg.file_processing.auth.api.dto.request.*;
import com.vandunxg.file_processing.auth.api.dto.response.LoginResponse;
import com.vandunxg.file_processing.auth.api.dto.response.RegisterResponse;
import com.vandunxg.file_processing.auth.api.mapper.AuthWebMapper;
import com.vandunxg.file_processing.auth.application.capability.PasswordChangeTokenReader;
import com.vandunxg.file_processing.auth.application.command.LogoutCommand;
import com.vandunxg.file_processing.auth.application.command.RefreshTokenCommand;
import com.vandunxg.file_processing.auth.application.exception.AuthErrorCode;
import com.vandunxg.file_processing.auth.application.exception.AuthException;
import com.vandunxg.file_processing.auth.application.service.AuthenticationCommandService;
import com.vandunxg.file_processing.auth.application.service.PasswordCommandService;
import com.vandunxg.file_processing.auth.application.service.RegistrationCommandService;
import com.vandunxg.file_processing.auth.application.service.SessionCommandService;
import com.vandunxg.file_processing.configuration.security.AuthenticatedUser;
import com.vandunxg.file_processing.configuration.web.CorsProperties;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.cors.CorsConfiguration;

@RestController
@RequestMapping("${app.api.prefix}/${app.api.version}/auth")
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-CONTROLLER")
@Tag(name = "Authentication", description = "Public authentication and authenticated session flows")
public class AuthController {

  private static final String REFRESH_COOKIE = "fps_refresh";
  private static final String CSRF_COOKIE = "fps_csrf";
  private static final String CSRF_HEADER = "X-CSRF-Token";

  private final RegistrationCommandService registrationCommandService;
  private final PasswordCommandService passwordCommandService;
  private final AuthenticationCommandService authenticationCommandService;
  private final SessionCommandService sessionCommandService;
  private final AuthWebMapper webMapper;
  private final PasswordChangeTokenReader passwordChangeTokenReader;
  private final AuthCookies authCookies;
  private final CorsProperties corsProperties;

  @Operation(summary = "Register a new operator account")
  @SecurityRequirements
  @PostMapping("/register")
  @ResponseStatus(HttpStatus.CREATED)
  public Response<RegisterResponse> register(
      @Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
    String clientIp = clientIp(http);
    log.info("[register] username={}", request.getUsername());
    var command = webMapper.toCommand(request, clientIp);
    var result = registrationCommandService.register(command);
    return Response.of(webMapper.toResponse(result));
  }

  @Operation(summary = "Verify an email address using an opaque token")
  @SecurityRequirements
  @PostMapping("/verify-email")
  public Response<RegisterResponse> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
    var result = registrationCommandService.verifyEmail(webMapper.toCommand(request));
    return Response.of(webMapper.toResponse(result));
  }

  @Operation(summary = "Resend a verification email (enumeration-safe)")
  @SecurityRequirements
  @PostMapping("/resend-verification")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void resendVerification(
      @Valid @RequestBody ResendVerificationRequest request, HttpServletRequest http) {
    registrationCommandService.resendVerificationEmail(
        webMapper.toCommand(request, clientIp(http)));
  }

  @Operation(
      summary = "Request a password reset email",
      description = "Accepted without revealing whether the username or email exists.")
  @SecurityRequirements
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Password-reset request accepted"),
    @ApiResponse(responseCode = "429", description = "Password-reset rate limit exceeded")
  })
  @PostMapping("/forgot-password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void forgotPassword(
      @Valid @RequestBody ForgotPasswordRequest request, HttpServletRequest http) {
    passwordCommandService.requestReset(webMapper.toCommand(request, clientIp(http)));
  }

  @Operation(summary = "Set a new password using an opaque reset token")
  @SecurityRequirements
  @PostMapping("/reset-password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void resetPassword(
      @Valid @RequestBody ResetPasswordRequest request, HttpServletRequest http) {
    passwordCommandService.reset(webMapper.toCommand(request, clientIp(http)));
  }

  @Operation(summary = "Change the authenticated user's password")
  @PostMapping("/change-password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void changePassword(
      @Valid @RequestBody ChangePasswordRequest request,
      @AuthenticationPrincipal AuthenticatedUser principal,
      HttpServletRequest http) {
    passwordCommandService.change(webMapper.toCommand(request, principal.userId(), clientIp(http)));
  }

  @Operation(
      summary = "Complete a forced first-login password change",
      description = "Requires a password-change JWT in the `Authorization` header.")
  @PostMapping("/complete-password-change")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void completePasswordChange(
      @Valid @RequestBody ChangePasswordRequest request,
      @RequestHeader(value = "Authorization", required = false) String authorization,
      HttpServletRequest http) {
    UUID userId = passwordChangeTokenReader.readUserId(authorization);
    passwordCommandService.complete(webMapper.toCommand(request, userId, clientIp(http)));
  }

  @Operation(
      summary = "Log in with username and password; returns an access token and auth cookies")
  @SecurityRequirements
  @PostMapping("/login")
  public Response<LoginResponse> login(
      @Valid @RequestBody LoginRequest request,
      HttpServletRequest http,
      HttpServletResponse response) {
    log.info("[login] username={}", request.getUsername());
    var command = webMapper.toCommand(request, clientIp(http), userAgent(http));
    var result = authenticationCommandService.login(command);
    authCookies.write(response, result.refreshToken());
    return Response.of(webMapper.toResponse(result));
  }

  @Operation(
      summary = "Rotate the HttpOnly refresh cookie",
      description =
          "Requires `fps_refresh` and a matching `fps_csrf`/`X-CSRF-Token` double-submit pair. A successful refresh rotates the refresh token and replaces both cookies.")
  @SecurityRequirements
  @ApiResponses({
    @ApiResponse(
        responseCode = "200",
        description = "Access token issued and both auth cookies rotated"),
    @ApiResponse(
        responseCode = "401",
        description = "Refresh token is invalid, expired, revoked, or reused"),
    @ApiResponse(responseCode = "403", description = "CSRF token is missing or does not match")
  })
  @PostMapping("/refresh")
  public Response<LoginResponse> refresh(
      @Parameter(
              name = REFRESH_COOKIE,
              in = ParameterIn.COOKIE,
              description = "HttpOnly opaque refresh token",
              required = true)
          @CookieValue(value = REFRESH_COOKIE, required = false)
          String refreshToken,
      @Parameter(
              name = CSRF_COOKIE,
              in = ParameterIn.COOKIE,
              description = "Readable CSRF token that must match the request header",
              required = true)
          @CookieValue(value = CSRF_COOKIE, required = false)
          String csrfCookie,
      @Parameter(
              name = CSRF_HEADER,
              in = ParameterIn.HEADER,
              description = "Must match the fps_csrf cookie",
              required = true)
          @RequestHeader(value = CSRF_HEADER, required = false)
          String csrfHeader,
      HttpServletRequest http,
      HttpServletResponse response) {
    if (!originIsAllowed(http) || !authCookies.csrfMatches(csrfCookie, csrfHeader)) {
      throw new AuthException(AuthErrorCode.AUTH_CSRF_TOKEN_INVALID);
    }
    var command =
        RefreshTokenCommand.builder()
            .refreshToken(refreshToken)
            .ipAddress(clientIp(http))
            .userAgent(userAgent(http))
            .build();
    var result = sessionCommandService.refresh(command);
    authCookies.write(response, result.refreshToken());
    return Response.of(webMapper.toResponse(result));
  }

  @Operation(summary = "Revoke the current session (logout this device)")
  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void logout(
      @AuthenticationPrincipal AuthenticatedUser principal,
      HttpServletRequest http,
      HttpServletResponse response) {
    log.info("[logout] userId={} sid={}", principal.userId(), principal.sessionId());
    sessionCommandService.logout(
        LogoutCommand.builder()
            .sessionId(principal.sessionId())
            .userId(principal.userId())
            .ipAddress(clientIp(http))
            .build());
    authCookies.clear(response);
  }

  private String clientIp(HttpServletRequest http) {
    return IpUtils.getRemoteIp(http);
  }

  private static String userAgent(HttpServletRequest http) {
    String ua = http.getHeader(HttpHeaders.USER_AGENT);
    if (ua == null) {
      return null;
    }
    return ua.length() > 255 ? ua.substring(0, 255) : ua;
  }

  private boolean originIsAllowed(HttpServletRequest http) {
    String origin = http.getHeader(HttpHeaders.ORIGIN);
    if (origin == null) {
      return false;
    }
    CorsConfiguration configuration = new CorsConfiguration();
    configuration.setAllowedOrigins(corsProperties.allowedOrigins());
    return configuration.checkOrigin(origin) != null;
  }
}
