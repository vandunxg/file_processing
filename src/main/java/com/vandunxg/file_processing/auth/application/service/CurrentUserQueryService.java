package com.vandunxg.file_processing.auth.application.service;

import com.vandunxg.file_processing.auth.application.exception.AuthErrorCode;
import com.vandunxg.file_processing.auth.application.exception.AuthException;
import com.vandunxg.file_processing.auth.application.mapper.UserResultMapper;
import com.vandunxg.file_processing.auth.application.query.GetCurrentUserQuery;
import com.vandunxg.file_processing.auth.application.result.MeResult;
import com.vandunxg.file_processing.auth.domain.UserRepository;
import com.vandunxg.file_processing.auth.domain.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-ME")
public class CurrentUserQueryService {

  private final UserRepository userRepository;
  private final UserResultMapper userResultMapper;

  @Transactional(readOnly = true)
  public MeResult me(GetCurrentUserQuery query) {
    log.info("[me] userId={}", query.userId());

    User user =
        userRepository
            .findById(query.userId())
            .orElseThrow(
                () -> {
                  log.warn("[me] user not found userId={}", query.userId());
                  return new AuthException(AuthErrorCode.USER_NOT_FOUND);
                });
    return userResultMapper.toMeResult(user);
  }
}
