package com.vandunxg.file_processing.auth.adapter.in.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

import com.vandunxg.common.models.dto.response.Response;
import com.vandunxg.common.web.support.IpUtils;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.request.*;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.response.LoginResponse;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.response.RegisterResponse;
import com.vandunxg.file_processing.auth.adapter.in.web.mapper.AuthWebMapper;
import com.vandunxg.file_processing.auth.adapter.out.security.PasswordChangeTokenDecoder;
import com.vandunxg.file_processing.auth.application.command.LogoutCommand;
import com.vandunxg.file_processing.auth.application.command.RefreshTokenCommand;
import com.vandunxg.file_processing.auth.application.port.in.*;
import com.vandunxg.file_processing.auth.application.port.out.RefreshTokenGeneratorPort;
import com.vandunxg.file_processing.auth.configuration.AuthProperties;
import com.vandunxg.file_processing.auth.domain.exception.AuthDomainException;
import com.vandunxg.file_processing.auth.domain.exception.AuthErrorCode;
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
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("${app.api.prefix}/${app.api.version}/auth")
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-CONTROLLER")
@Tag(name = "Authentication", description = "Public authentication and authenticated session flows")
public class AuthController {

  private static final String AUTH_COOKIE_PATH = "/api/v1/auth";
  private static final String REFRESH_COOKIE = "fps_refresh";
  private static final String CSRF_COOKIE = "fps_csrf";
  private static final String CSRF_HEADER = "X-CSRF-Token";

  private final RegisterUseCase registerUseCase;
  private final VerifyEmailUseCase verifyEmailUseCase;
  private final ResendVerificationEmailUseCase resendVerificationEmailUseCase;
  private final ForgotPasswordUseCase forgotPasswordUseCase;
  private final ResetPasswordUseCase resetPasswordUseCase;
  private final ChangePasswordUseCase changePasswordUseCase;
  private final LoginUseCase loginUseCase;
  private final RefreshTokenUseCase refreshTokenUseCase;
  private final LogoutUseCase logoutUseCase;
  private final AuthWebMapper webMapper;
  private final PasswordChangeTokenDecoder passwordChangeTokenDecoder;
  private final RefreshTokenGeneratorPort refreshTokenGeneratorPort;
  private final AuthProperties authProperties;

  @Operation(summary = "Register a new operator account")
  @SecurityRequirements
  @PostMapping("/register")
  @ResponseStatus(HttpStatus.CREATED)
  public Response<RegisterResponse> register(
      @Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
    String clientIp = clientIp(http);
    log.info("[register] username={}", request.getUsername());
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

  @Operation(
      summary = "Request a password reset email",
      description = "Unknown usernames or emails return `404 USER_NOT_FOUND`.")
  @SecurityRequirements
  @ApiResponses({
    @ApiResponse(responseCode = "204", description = "Password-reset request accepted"),
    @ApiResponse(responseCode = "404", description = "User not found"),
    @ApiResponse(responseCode = "429", description = "Password-reset rate limit exceeded")
  })
  @PostMapping("/forgot-password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void forgotPassword(
      @Valid @RequestBody ForgotPasswordRequest request, HttpServletRequest http) {
    forgotPasswordUseCase.request(webMapper.toCommand(request, clientIp(http)));
  }

  @Operation(summary = "Set a new password using an opaque reset token")
  @SecurityRequirements
  @PostMapping("/reset-password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void resetPassword(
      @Valid @RequestBody ResetPasswordRequest request, HttpServletRequest http) {
    resetPasswordUseCase.reset(webMapper.toCommand(request, clientIp(http)));
  }

  @Operation(summary = "Change the authenticated user's password")
  @PostMapping("/change-password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void changePassword(
      @Valid @RequestBody ChangePasswordRequest request,
      @AuthenticationPrincipal Jwt jwt,
      HttpServletRequest http) {
    changePasswordUseCase.change(webMapper.toCommand(request, subjectAsUuid(jwt), clientIp(http)));
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
    Jwt jwt = passwordChangeTokenDecoder.decode(authorization);
    changePasswordUseCase.complete(
        webMapper.toCommand(request, subjectAsUuid(jwt), clientIp(http)));
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
    var result = loginUseCase.login(command);
    setAuthCookies(response, result.getRefreshToken());
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
    if (!csrfMatches(csrfCookie, csrfHeader)) {
      throw new AuthDomainException(AuthErrorCode.CSRF_TOKEN_INVALID);
    }
    var command =
        RefreshTokenCommand.builder()
            .refreshToken(refreshToken)
            .ipAddress(clientIp(http))
            .userAgent(userAgent(http))
            .build();
    var result = refreshTokenUseCase.refresh(command);
    setAuthCookies(response, result.getRefreshToken());
    return Response.of(webMapper.toResponse(result));
  }

  @Operation(summary = "Revoke the current session (logout this device)")
  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void logout(
      @AuthenticationPrincipal Jwt jwt, HttpServletRequest http, HttpServletResponse response) {
    UUID userId = subjectAsUuid(jwt);
    UUID sid = sidAsUuid(jwt);
    log.info("[logout] userId={} sid={}", userId, sid);
    logoutUseCase.logout(
        LogoutCommand.builder().sessionId(sid).userId(userId).ipAddress(clientIp(http)).build());
    clearAuthCookies(response);
  }

  private String clientIp(HttpServletRequest http) {
    return IpUtils.getRemoteIp(http);
  }

  private static String userAgent(HttpServletRequest http) {
    String ua = http.getHeader("User-Agent");
    if (ua == null) {
      return null;
    }
    return ua.length() > 255 ? ua.substring(0, 255) : ua;
  }

  private static UUID subjectAsUuid(Jwt jwt) {
    return UUID.fromString(Objects.requireNonNull(jwt.getSubject()));
  }

  private static UUID sidAsUuid(Jwt jwt) {
    return UUID.fromString(Objects.requireNonNull(jwt.getClaimAsString("sid")));
  }

  private void setAuthCookies(HttpServletResponse response, String refreshToken) {
    if (refreshToken == null) {
      return;
    }
    response.addHeader("Set-Cookie", cookie(REFRESH_COOKIE, refreshToken, true).toString());
    response.addHeader(
        "Set-Cookie", cookie(CSRF_COOKIE, refreshTokenGeneratorPort.generate(), false).toString());
  }

  private ResponseCookie cookie(String name, String value, boolean httpOnly) {
    return cookie(name, value, httpOnly, authProperties.refresh().tokenTtl());
  }

  private ResponseCookie cookie(String name, String value, boolean httpOnly, Duration maxAge) {
    return ResponseCookie.from(name, value)
        .httpOnly(httpOnly)
        .secure(authProperties.refresh().cookieSecure())
        .sameSite("Strict")
        .path(AUTH_COOKIE_PATH)
        .maxAge(maxAge)
        .build();
  }

  private void clearAuthCookies(HttpServletResponse response) {
    response.addHeader("Set-Cookie", cookie(REFRESH_COOKIE, "", true, Duration.ZERO).toString());
    response.addHeader("Set-Cookie", cookie(CSRF_COOKIE, "", false, Duration.ZERO).toString());
  }

  private static boolean csrfMatches(String csrfCookie, String csrfHeader) {
    return csrfCookie != null
        && csrfHeader != null
        && MessageDigest.isEqual(
            csrfCookie.getBytes(StandardCharsets.UTF_8),
            csrfHeader.getBytes(StandardCharsets.UTF_8));
  }
}
