package com.vandunxg.file_processing.auth.adapter.in.web;

import java.util.UUID;

import com.vandunxg.common.models.dto.response.Response;
import com.vandunxg.common.web.support.IpUtils;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.response.SessionResponse;
import com.vandunxg.file_processing.auth.adapter.in.web.mapper.AuthWebMapper;
import com.vandunxg.file_processing.auth.application.command.RevokeAllSessionsCommand;
import com.vandunxg.file_processing.auth.application.command.RevokeSessionCommand;
import com.vandunxg.file_processing.auth.application.port.in.ListSessionsUseCase;
import com.vandunxg.file_processing.auth.application.port.in.RevokeAllSessionsUseCase;
import com.vandunxg.file_processing.auth.application.port.in.RevokeSessionUseCase;
import com.vandunxg.file_processing.auth.application.query.ListSessionsQuery;
import com.vandunxg.file_processing.auth.domain.model.RevocationReason;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${app.api.prefix}/${app.api.version}/me/sessions")
@RequiredArgsConstructor
@Tag(name = "Current user sessions", description = "Authenticated caller session management")
public class CurrentUserSessionController {

  private final RevokeAllSessionsUseCase revokeAllSessionsUseCase;
  private final RevokeSessionUseCase revokeSessionUseCase;
  private final ListSessionsUseCase listSessionsUseCase;
  private final AuthWebMapper webMapper;

  @Operation(summary = "List the caller's active sessions")
  @GetMapping
  @PreAuthorize("hasPermission(null, 'session:self_read')")
  public Response<java.util.List<SessionResponse>> listSessions(@AuthenticationPrincipal Jwt jwt) {
    UUID userId = subjectAsUuid(jwt);
    UUID currentSid = sidAsUuid(jwt);
    var results =
        listSessionsUseCase.list(
            ListSessionsQuery.builder().userId(userId).currentSessionId(currentSid).build());
    return Response.of(results.stream().map(webMapper::toResponse).toList());
  }

  @Operation(summary = "Revoke one of the caller's sessions by id")
  @DeleteMapping("/{sessionId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasPermission(null, 'session:self_delete')")
  public void revokeSession(
      @PathVariable UUID sessionId, @AuthenticationPrincipal Jwt jwt, HttpServletRequest http) {
    revokeSessionUseCase.revoke(
        RevokeSessionCommand.builder()
            .sessionId(sessionId)
            .callerUserId(subjectAsUuid(jwt))
            .callerSessionId(sidAsUuid(jwt))
            .ipAddress(IpUtils.getRemoteIp(http))
            .build());
  }

  @Operation(summary = "Revoke every active session of the caller (sign out everywhere)")
  @PostMapping("/revoke-all")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasPermission(null, 'session:self_delete')")
  public void revokeAll(
      @AuthenticationPrincipal Jwt jwt, HttpServletRequest http, HttpServletResponse response) {
    revokeAllSessionsUseCase.revokeAll(
        RevokeAllSessionsCommand.builder()
            .userId(subjectAsUuid(jwt))
            .reason(RevocationReason.USER_TRIGGERED)
            .ipAddress(IpUtils.getRemoteIp(http))
            .build());
    response.setHeader("Set-Cookie", "fps_refresh=; Path=/api/v1/auth; Max-Age=0; HttpOnly");
    response.addHeader("Set-Cookie", "fps_csrf=; Path=/api/v1/auth; Max-Age=0");
  }

  private static UUID subjectAsUuid(Jwt jwt) {
    return UUID.fromString(jwt.getSubject());
  }

  private static UUID sidAsUuid(Jwt jwt) {
    return UUID.fromString(jwt.getClaimAsString("sid"));
  }
}
