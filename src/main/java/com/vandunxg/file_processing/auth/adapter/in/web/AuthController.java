package com.vandunxg.file_processing.auth.adapter.in.web;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.vandunxg.common.models.dto.response.Response;
import com.vandunxg.common.web.support.IpUtils;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.request.*;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.response.LoginResponse;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.response.MeResponse;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.response.RegisterResponse;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.response.SessionResponse;
import com.vandunxg.file_processing.auth.adapter.in.web.mapper.AuthWebMapper;
import com.vandunxg.file_processing.auth.application.command.LogoutCommand;
import com.vandunxg.file_processing.auth.application.command.RevokeAllSessionsCommand;
import com.vandunxg.file_processing.auth.application.command.RevokeSessionCommand;
import com.vandunxg.file_processing.auth.application.port.in.*;
import com.vandunxg.file_processing.auth.application.query.GetCurrentUserQuery;
import com.vandunxg.file_processing.auth.application.query.ListSessionsQuery;
import com.vandunxg.file_processing.auth.domain.model.RevocationReason;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("${app.api.prefix}/${app.api.version}/auth")
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-CONTROLLER")
public class AuthController {

  private final RegisterUseCase registerUseCase;
  private final VerifyEmailUseCase verifyEmailUseCase;
  private final ResendVerificationEmailUseCase resendVerificationEmailUseCase;
  private final LoginUseCase loginUseCase;
  private final RefreshTokenUseCase refreshTokenUseCase;
  private final LogoutUseCase logoutUseCase;
  private final RevokeAllSessionsUseCase revokeAllSessionsUseCase;
  private final RevokeSessionUseCase revokeSessionUseCase;
  private final ListSessionsUseCase listSessionsUseCase;
  private final GetCurrentUserUseCase getCurrentUserUseCase;
  private final AuthWebMapper webMapper;

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

  @Operation(summary = "Log in with username and password; returns access + refresh tokens")
  @SecurityRequirements
  @PostMapping("/login")
  public Response<LoginResponse> login(
      @Valid @RequestBody LoginRequest request, HttpServletRequest http) {
    log.info("[login] username={}", request.getUsername());
    var command = webMapper.toCommand(request, clientIp(http), userAgent(http));
    var result = loginUseCase.login(command);
    return Response.of(webMapper.toResponse(result));
  }

  @Operation(summary = "Rotate the refresh token; returns fresh access + refresh tokens")
  @SecurityRequirements
  @PostMapping("/refresh")
  public Response<LoginResponse> refresh(
      @Valid @RequestBody RefreshTokenRequest request, HttpServletRequest http) {
    var command = webMapper.toCommand(request, clientIp(http), userAgent(http));
    var result = refreshTokenUseCase.refresh(command);
    return Response.of(webMapper.toResponse(result));
  }

  @Operation(summary = "Revoke the current session (logout this device)")
  @PostMapping("/logout")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void logout(@AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
    UUID userId = subjectAsUuid(jwt);
    UUID sid = sidAsUuid(jwt);
    log.info("[logout] userId={} sid={}", userId, sid);
    logoutUseCase.logout(
        LogoutCommand.builder().sessionId(sid).userId(userId).ipAddress(clientIp(http)).build());
  }

  @Operation(summary = "Revoke every active session of the caller (sign out everywhere)")
  @PostMapping("/sessions/revoke-all")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void revokeAll(@AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
    UUID userId = subjectAsUuid(jwt);
    log.info("[revokeAll] userId={}", userId);
    revokeAllSessionsUseCase.revokeAll(
        RevokeAllSessionsCommand.builder()
            .userId(userId)
            .reason(RevocationReason.USER_TRIGGERED)
            .ipAddress(clientIp(http))
            .build());
  }

  @Operation(summary = "Return the authenticated user's profile")
  @GetMapping("/me")
  public Response<MeResponse> me(@AuthenticationPrincipal Jwt jwt) {
    UUID userId = subjectAsUuid(jwt);
    var result = getCurrentUserUseCase.me(GetCurrentUserQuery.builder().userId(userId).build());
    return Response.of(webMapper.toResponse(result));
  }

  @Operation(summary = "List the caller's active sessions")
  @GetMapping("/sessions")
  public Response<List<SessionResponse>> listSessions(@AuthenticationPrincipal Jwt jwt) {
    UUID userId = subjectAsUuid(jwt);
    UUID currentSid = sidAsUuid(jwt);
    var results =
        listSessionsUseCase.list(
            ListSessionsQuery.builder().userId(userId).currentSessionId(currentSid).build());
    return Response.of(results.stream().map(webMapper::toResponse).toList());
  }

  @Operation(summary = "Revoke one of the caller's sessions by id")
  @DeleteMapping("/sessions/{sid}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void revokeSession(
      @PathVariable UUID sid, @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
    UUID callerUserId = subjectAsUuid(jwt);
    UUID callerSid = sidAsUuid(jwt);
    log.info("[revokeSession] callerUserId={} targetSid={}", callerUserId, sid);
    revokeSessionUseCase.revoke(
        RevokeSessionCommand.builder()
            .sessionId(sid)
            .callerUserId(callerUserId)
            .callerSessionId(callerSid)
            .ipAddress(clientIp(http))
            .build());
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
}
