package com.vandunxg.file_processing.auth.adapter.out.persistence.entity.custom;

import java.util.List;

import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.UserEntity;
import com.vandunxg.file_processing.auth.application.query.UserSearchQuery;

public interface JpaUserRepositoryCustom {
  Long count(UserSearchQuery query);

  List<UserEntity> search(UserSearchQuery query);
}
