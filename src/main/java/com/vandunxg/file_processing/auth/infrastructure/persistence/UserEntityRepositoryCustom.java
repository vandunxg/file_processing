package com.vandunxg.file_processing.auth.infrastructure.persistence;

import java.util.List;

import com.vandunxg.file_processing.auth.application.query.UserSearchQuery;
import com.vandunxg.file_processing.auth.infrastructure.persistence.entity.UserEntity;

public interface UserEntityRepositoryCustom {
  Long count(UserSearchQuery query);

  List<UserEntity> search(UserSearchQuery query);
}
