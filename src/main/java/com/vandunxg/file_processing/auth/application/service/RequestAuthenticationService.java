package com.vandunxg.file_processing.auth.application.service;

import com.vandunxg.file_processing.auth.application.exception.AuthErrorCode;
import com.vandunxg.file_processing.auth.application.exception.AuthException;
import com.vandunxg.file_processing.auth.application.result.RequestAuthenticationResult;
import com.vandunxg.file_processing.auth.domain.UserRepository;
import com.vandunxg.file_processing.auth.domain.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RequestAuthenticationService {

  private final UserRepository userRepository;
  private final AuthorityService authorityService;

  @Transactional(readOnly = true)
  public RequestAuthenticationResult resolve(java.util.UUID userId) {
    User user = userRepository.findById(userId).orElseThrow(this::invalidCredentials);
    if (!user.isActive()) {
      throw invalidCredentials();
    }
    return new RequestAuthenticationResult(
        user.getId(), user.getUsername(), authorityService.permissionsFor(user));
  }

  private AuthException invalidCredentials() {
    return new AuthException(AuthErrorCode.AUTH_INVALID_CREDENTIALS);
  }
}
