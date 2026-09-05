package com.vandunxg.file_processing.auth.infrastructure.persistence;

import java.util.List;

import com.vandunxg.file_processing.auth.application.query.RoleSearchQuery;
import com.vandunxg.file_processing.auth.infrastructure.persistence.entity.RoleEntity;

public interface RoleEntityRepositoryCustom {
  Long count(RoleSearchQuery query);

  List<RoleEntity> search(RoleSearchQuery query);
}
