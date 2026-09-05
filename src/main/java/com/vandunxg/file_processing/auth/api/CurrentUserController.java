package com.vandunxg.file_processing.auth.api;

import com.vandunxg.common.models.dto.response.Response;
import com.vandunxg.file_processing.auth.api.dto.response.MeResponse;
import com.vandunxg.file_processing.auth.api.mapper.AuthWebMapper;
import com.vandunxg.file_processing.auth.application.query.GetCurrentUserQuery;
import com.vandunxg.file_processing.auth.application.service.CurrentUserQueryService;
import com.vandunxg.file_processing.configuration.security.AuthenticatedUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("${app.api.prefix}/${app.api.version}/me")
@RequiredArgsConstructor
@Tag(name = "Current user", description = "Authenticated caller profile")
public class CurrentUserController {

  private final CurrentUserQueryService currentUserQueryService;
  private final AuthWebMapper webMapper;

  @Operation(summary = "Return the authenticated user's profile")
  @GetMapping
  @PreAuthorize("hasPermission(null, 'user:self_read')")
  public Response<MeResponse> me(@AuthenticationPrincipal AuthenticatedUser principal) {
    var result = currentUserQueryService.me(new GetCurrentUserQuery(principal.userId()));
    return Response.of(webMapper.toResponse(result));
  }
}
