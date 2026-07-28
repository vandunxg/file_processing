package com.vandunxg.file_processing.auth.application.service;

import com.vandunxg.file_processing.auth.application.port.in.ResolveRequestAuthenticationUseCase;
import com.vandunxg.file_processing.auth.application.port.out.UserRepositoryPort;
import com.vandunxg.file_processing.auth.application.result.RequestAuthenticationResult;
import com.vandunxg.file_processing.auth.domain.exception.AuthDomainException;
import com.vandunxg.file_processing.auth.domain.exception.AuthErrorCode;
import com.vandunxg.file_processing.auth.domain.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ResolveRequestAuthenticationService implements ResolveRequestAuthenticationUseCase {

  private final UserRepositoryPort userRepositoryPort;
  private final AuthorityService authorityService;

  @Override
  @Transactional(readOnly = true)
  public RequestAuthenticationResult resolve(java.util.UUID userId) {
    User user = userRepositoryPort.findById(userId).orElseThrow(this::invalidCredentials);
    if (!user.isActive()) {
      throw invalidCredentials();
    }
    return new RequestAuthenticationResult(
        user.getId(), user.getUsername(), authorityService.permissionsFor(user));
  }

  private AuthDomainException invalidCredentials() {
    return new AuthDomainException(AuthErrorCode.INVALID_CREDENTIALS);
  }
}
