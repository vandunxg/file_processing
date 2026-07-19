package com.vandunxg.file_processing.auth.application.service;

import com.vandunxg.file_processing.auth.application.port.in.GetCurrentUserUseCase;
import com.vandunxg.file_processing.auth.application.port.out.UserRepositoryPort;
import com.vandunxg.file_processing.auth.application.query.GetCurrentUserQuery;
import com.vandunxg.file_processing.auth.application.result.MeResult;
import com.vandunxg.file_processing.auth.domain.exception.AuthDomainException;
import com.vandunxg.file_processing.auth.domain.exception.AuthErrorCode;
import com.vandunxg.file_processing.auth.domain.model.Role;
import com.vandunxg.file_processing.auth.domain.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-ME")
public class GetCurrentUserService implements GetCurrentUserUseCase {

  private final UserRepositoryPort userRepositoryPort;

  @Override
  @Transactional(readOnly = true)
  public MeResult me(GetCurrentUserQuery query) {
    User user =
        userRepositoryPort
            .findById(query.getUserId())
            .orElseThrow(
                () -> {
                  log.warn("[me] user not found userId={}", query.getUserId());
                  return new AuthDomainException(AuthErrorCode.USER_NOT_FOUND);
                });
    return MeResult.builder()
        .userId(user.getId())
        .username(user.getUsername())
        .email(user.getEmail())
        .displayName(user.getDisplayName())
        .roles(
            user.getRoles() == null
                ? java.util.List.of()
                : user.getRoles().stream().map(Role::getCode).toList())
        .status(user.getStatus())
        .build();
  }
}
