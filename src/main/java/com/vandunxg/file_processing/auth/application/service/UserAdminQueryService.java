package com.vandunxg.file_processing.auth.application.service;

import java.util.List;
import java.util.UUID;

import com.vandunxg.common.models.dto.PageDTO;
import com.vandunxg.file_processing.auth.application.capability.UserSearchRepository;
import com.vandunxg.file_processing.auth.application.exception.AuthErrorCode;
import com.vandunxg.file_processing.auth.application.exception.AuthException;
import com.vandunxg.file_processing.auth.application.query.UserSearchQuery;
import com.vandunxg.file_processing.auth.domain.UserRepository;
import com.vandunxg.file_processing.auth.domain.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Administrative reads over users. */
@Service
@RequiredArgsConstructor
public class UserAdminQueryService {

  private final UserRepository userRepository;
  private final UserSearchRepository userSearchRepository;

  @Transactional(readOnly = true)
  public List<User> list() {
    return userRepository.findAll();
  }

  @Transactional(readOnly = true)
  public PageDTO<User> search(UserSearchQuery query) {
    long count = userSearchRepository.count(query);
    if (count == 0) {
      return PageDTO.of(List.of(), query.getPageIndex(), query.getPageSize(), 0);
    }
    return PageDTO.of(
        userSearchRepository.search(query), query.getPageIndex(), query.getPageSize(), count);
  }

  @Transactional(readOnly = true)
  public User detail(UUID userId) {
    return userRepository
        .findById(userId)
        .orElseThrow(() -> new AuthException(AuthErrorCode.USER_NOT_FOUND));
  }
}
