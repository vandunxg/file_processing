package com.vandunxg.file_processing.auth.adapter.in.web;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.vandunxg.common.models.dto.PageDTO;
import com.vandunxg.common.models.dto.response.PagingResponse;
import com.vandunxg.common.models.dto.response.Response;
import com.vandunxg.common.models.validator.ValidatePaging;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.request.UserSearchRequest;
import com.vandunxg.file_processing.auth.adapter.in.web.mapper.UserWebMapper;
import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.UserEntity;
import com.vandunxg.file_processing.auth.application.query.UserSearchQuery;
import com.vandunxg.file_processing.auth.application.service.AdminUserService;
import com.vandunxg.file_processing.auth.configuration.security.AuthenticatedUser;
import com.vandunxg.file_processing.auth.domain.model.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("${app.api.prefix}/${app.api.version}/users")
@RequiredArgsConstructor
@Tag(
    name = "Admin users",
    description = "Bearer access token required. `all:manage` satisfies every user permission.")
public class UserManagementController {

  private final AdminUserService adminUserService;
  private final UserWebMapper userWebMapper;

  @Operation(
      summary = "Create a user with a temporary password",
      description = "Requires `user:create`.")
  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  @PreAuthorize("hasPermission(null, 'user:create')")
  public Response<UserResponse> create(
      @AuthenticationPrincipal AuthenticatedUser principal,
      @Valid @RequestBody CreateUserRequest request) {
    return Response.of(
        userWebMapper.toResponse(
            adminUserService.create(
                principal.userId(),
                request.username(),
                request.email(),
                request.displayName(),
                request.temporaryPassword(),
                request.roleIds(),
                request.autoVerifyEmail())));
  }

  @Operation(summary = "List managed users", description = "Requires `user:read`.")
  @GetMapping
  @PreAuthorize("hasPermission(null, 'user:read')")
  public PagingResponse<UserResponse> list(
      @ValidatePaging(sortModel = UserEntity.class) UserSearchRequest request) {

    UserSearchQuery searchQuery = userWebMapper.toQuery(request);
    PageDTO<User> resultPage = adminUserService.search(searchQuery);

    return new PagingResponse<>(resultPage, userWebMapper::toResponse);
  }

  @Operation(summary = "Read a managed user", description = "Requires `user:read`.")
  @GetMapping("/{userId}")
  @PreAuthorize("hasPermission(null, 'user:read')")
  public Response<UserResponse> detail(@PathVariable UUID userId) {
    return Response.of(userWebMapper.toResponse(adminUserService.detail(userId)));
  }

  @Operation(summary = "Update a user's profile and roles", description = "Requires `user:update`.")
  @PostMapping("/{userId}/update")
  @PreAuthorize("hasPermission(null, 'user:update')")
  public Response<UserResponse> update(
      @AuthenticationPrincipal AuthenticatedUser principal,
      @PathVariable UUID userId,
      @Valid @RequestBody UpdateUserRequest request) {
    return Response.of(
        userWebMapper.toResponse(
            adminUserService.update(
                principal.userId(),
                userId,
                request.email(),
                request.displayName(),
                request.roleIds())));
  }

  @Operation(
      summary = "Disable a user and revoke every session",
      description = "Requires `user:update`.")
  @PostMapping("/{userId}/disable")
  @PreAuthorize("hasPermission(null, 'user:update')")
  public Response<UserResponse> disable(
      @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID userId) {
    return Response.of(
        userWebMapper.toResponse(adminUserService.disable(principal.userId(), userId)));
  }

  @Operation(summary = "Enable a disabled user", description = "Requires `user:update`.")
  @PostMapping("/{userId}/enable")
  @PreAuthorize("hasPermission(null, 'user:update')")
  public Response<UserResponse> enable(
      @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID userId) {
    return Response.of(
        userWebMapper.toResponse(adminUserService.enable(principal.userId(), userId)));
  }

  @Operation(summary = "Clear a user's failed-login lock", description = "Requires `user:update`.")
  @PostMapping("/{userId}/unlock")
  @PreAuthorize("hasPermission(null, 'user:update')")
  public Response<UserResponse> unlock(
      @AuthenticationPrincipal AuthenticatedUser principal, @PathVariable UUID userId) {
    return Response.of(
        userWebMapper.toResponse(adminUserService.unlock(principal.userId(), userId)));
  }

  @Operation(
      summary = "Reset a user to a temporary password",
      description = "Requires `user:update`.")
  @PostMapping("/{userId}/reset-password")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  @PreAuthorize("hasPermission(null, 'user:update')")
  public void resetPassword(
      @AuthenticationPrincipal AuthenticatedUser principal,
      @PathVariable UUID userId,
      @Valid @RequestBody TemporaryPasswordRequest request) {
    adminUserService.resetTemporaryPassword(
        principal.userId(), userId, request.temporaryPassword());
  }

  public record CreateUserRequest(
      @Schema(example = "operator02") @NotBlank @Size(min = 3, max = 64) String username,
      @Schema(example = "operator02@example.com") @NotBlank @Email @Size(max = 254) String email,
      @Schema(example = "Operator Two") @NotBlank @Size(min = 2, max = 150) String displayName,
      @Schema(format = "password") @NotBlank String temporaryPassword,
      @NotEmpty Set<UUID> roleIds,
      boolean autoVerifyEmail) {}

  public record UpdateUserRequest(
      @NotBlank @Email @Size(max = 254) String email,
      @NotBlank @Size(min = 2, max = 150) String displayName,
      @NotEmpty Set<UUID> roleIds) {}

  public record TemporaryPasswordRequest(
      @Schema(format = "password") @NotBlank String temporaryPassword) {}

  public record UserResponse(
      UUID id,
      String username,
      String email,
      String displayName,
      String status,
      boolean mustChangePassword,
      int credentialVersion,
      List<String> roles) {}
}
