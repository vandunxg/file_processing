package com.vandunxg.file_processing.auth.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.vandunxg.file_processing.auth.application.exception.AuthErrorCode;
import com.vandunxg.file_processing.auth.application.exception.AuthException;
import com.vandunxg.file_processing.auth.application.result.RequestAuthenticationResult;
import com.vandunxg.file_processing.auth.domain.UserRepository;
import com.vandunxg.file_processing.auth.domain.model.User;
import com.vandunxg.file_processing.auth.domain.model.UserStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ResolveRequestAuthenticationServiceTest {

  @Mock private UserRepository userRepository;
  @Mock private AuthorityService authorityService;

  private RequestAuthenticationService service;

  @BeforeEach
  void setUp() {
    service = new RequestAuthenticationService(userRepository, authorityService);
  }

  @Test
  void resolveReturnsCurrentPermissionsWhenUserIsActive() {
    UUID userId = UUID.randomUUID();
    User user = user(userId, UserStatus.ACTIVE);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    when(authorityService.permissionsFor(user))
        .thenReturn(List.of("file:read", "file:self_create"));

    assertThat(service.resolve(userId))
        .isEqualTo(
            new RequestAuthenticationResult(
                userId, "operator01", List.of("file:read", "file:self_create")));
  }

  @Test
  void resolveThrowsInvalidCredentialsWhenUserIsMissing() {
    UUID userId = UUID.randomUUID();
    when(userRepository.findById(userId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.resolve(userId))
        .isInstanceOf(AuthException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.AUTH_INVALID_CREDENTIALS);

    verifyNoInteractions(authorityService);
  }

  @Test
  void resolveThrowsInvalidCredentialsWhenUserIsDisabled() {
    UUID userId = UUID.randomUUID();
    when(userRepository.findById(userId))
        .thenReturn(Optional.of(user(userId, UserStatus.DISABLED)));

    assertThatThrownBy(() -> service.resolve(userId))
        .isInstanceOf(AuthException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.AUTH_INVALID_CREDENTIALS);

    verifyNoInteractions(authorityService);
  }

  private static User user(UUID userId, UserStatus status) {
    return User.builder()
        .id(userId)
        .username("operator01")
        .normalizedUsername("operator01")
        .email("operator01@example.com")
        .normalizedEmail("operator01@example.com")
        .displayName("Operator One")
        .passwordHash("{bcrypt}current")
        .status(status)
        .credentialVersion(1)
        .build();
  }
}
