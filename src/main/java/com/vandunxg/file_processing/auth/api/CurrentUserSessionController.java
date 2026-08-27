package com.vandunxg.file_processing.auth.api;

import java.util.UUID;

import com.vandunxg.common.models.dto.response.Response;
import com.vandunxg.common.web.support.IpUtils;
import com.vandunxg.file_processing.auth.api.dto.response.SessionResponse;
import com.vandunxg.file_processing.auth.api.mapper.AuthWebMapper;
import com.vandunxg.file_processing.auth.application.command.RevokeAllSessionsCommand;
import com.vandunxg.file_processing.auth.application.command.RevokeSessionCommand;
import com.vandunxg.file_processing.auth.application.query.ListSessionsQuery;
import com.vandunxg.file_processing.auth.application.service.SessionCommandService;
import com.vandunxg.file_processing.auth.application.service.SessionQueryService;
import com.vandunxg.file_processing.auth.domain.model.RevocationReason;
import com.vandunxg.file_processing.configuration.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("${app.api.prefix}/${app.api.version}/me/sessions")
@RequiredArgsConstructor
@Tag(name = "Current user sessions", description = "Authenticated caller session management")
public class CurrentUserSessionController {

  private static final String HTTP_HEADER_SET_COOKIE = "Set-Cookie";
  private final SessionQueryService sessionQueryService;
  private final SessionCommandService sessionCommandService;
  private final AuthWebMapper webMapper;

  @Operation(summary = "List the caller's active sessions")
  @GetMapping
  @PreAuthorize("hasPermission(null, 'session:self_read')")
  public Response<java.util.List<SessionResponse>> listSessions(
      @AuthenticationPrincipal AuthenticatedUser principal) {
    var results =
        sessionQueryService.list(
            ListSessionsQuery.builder()
                .userId(principal.userId())
                .currentSessionId(principal.sessionId())
                .build());
    return Response.of(results.stream().map(webMapper::toResponse).toList());
  }

  @Operation(summary = "Revoke one of the caller's sessions by id")
  @DeleteMapping("/{sessionId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasPermission(null, 'session:self_delete')")
  public void revokeSession(
      @PathVariable UUID sessionId,
      @AuthenticationPrincipal AuthenticatedUser principal,
      HttpServletRequest http) {
    sessionCommandService.revoke(
        RevokeSessionCommand.builder()
            .sessionId(sessionId)
            .callerUserId(principal.userId())
            .callerSessionId(principal.sessionId())
            .ipAddress(IpUtils.getRemoteIp(http))
            .build());
  }

  @Operation(summary = "Revoke every active session of the caller (sign out everywhere)")
  @PostMapping("/revoke-all")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasPermission(null, 'session:self_delete')")
  public void revokeAll(
      @AuthenticationPrincipal AuthenticatedUser principal,
      HttpServletRequest http,
      HttpServletResponse response) {
    sessionCommandService.revokeAll(
        RevokeAllSessionsCommand.builder()
            .userId(principal.userId())
            .reason(RevocationReason.USER_TRIGGERED)
            .ipAddress(IpUtils.getRemoteIp(http))
            .build());
    response.setHeader(
        HTTP_HEADER_SET_COOKIE, "fps_refresh=; Path=/api/v1/auth; Max-Age=0; HttpOnly");
    response.addHeader(HTTP_HEADER_SET_COOKIE, "fps_csrf=; Path=/api/v1/auth; Max-Age=0");
  }
}
