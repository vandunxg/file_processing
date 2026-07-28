package com.vandunxg.file_processing.auth.adapter.in.web;

import com.vandunxg.common.models.dto.response.Response;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.response.MeResponse;
import com.vandunxg.file_processing.auth.adapter.in.web.mapper.AuthWebMapper;
import com.vandunxg.file_processing.auth.application.port.in.GetCurrentUserUseCase;
import com.vandunxg.file_processing.auth.application.query.GetCurrentUserQuery;
import com.vandunxg.file_processing.auth.configuration.security.AuthenticatedUser;
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

  private final GetCurrentUserUseCase getCurrentUserUseCase;
  private final AuthWebMapper webMapper;

  @Operation(summary = "Return the authenticated user's profile")
  @GetMapping
  @PreAuthorize("hasPermission(null, 'user:self_read')")
  public Response<MeResponse> me(@AuthenticationPrincipal AuthenticatedUser principal) {
    var result =
        getCurrentUserUseCase.me(GetCurrentUserQuery.builder().userId(principal.userId()).build());
    return Response.of(webMapper.toResponse(result));
  }
}
