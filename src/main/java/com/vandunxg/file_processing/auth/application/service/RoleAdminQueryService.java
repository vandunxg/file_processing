package com.vandunxg.file_processing.auth.application.service;

import java.util.List;
import java.util.UUID;

import com.vandunxg.common.models.dto.PageDTO;
import com.vandunxg.file_processing.auth.application.capability.RoleSearchRepository;
import com.vandunxg.file_processing.auth.application.exception.AuthErrorCode;
import com.vandunxg.file_processing.auth.application.exception.AuthException;
import com.vandunxg.file_processing.auth.application.query.RoleSearchQuery;
import com.vandunxg.file_processing.auth.domain.RoleRepository;
import com.vandunxg.file_processing.auth.domain.model.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Administrative reads over roles. */
@Service
@RequiredArgsConstructor
public class RoleAdminQueryService {

  private final RoleRepository roleRepository;
  private final RoleSearchRepository roleSearchRepository;

  @Transactional(readOnly = true)
  public PageDTO<Role> search(RoleSearchQuery query) {
    long count = roleSearchRepository.count(query);

    if (count == 0) {
      return PageDTO.of(List.of(), query.getPageIndex(), query.getPageSize(), 0);
    }

    return PageDTO.of(
        roleSearchRepository.search(query), query.getPageIndex(), query.getPageSize(), count);
  }

  @Transactional(readOnly = true)
  public Role detail(UUID roleId) {
    return roleRepository
        .findById(roleId)
        .orElseThrow(() -> new AuthException(AuthErrorCode.ROLE_NOT_FOUND));
  }
}
