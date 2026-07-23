package com.vandunxg.file_processing.auth.adapter.out.persistence.entity.custom;

import java.util.List;

import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.RoleEntity;
import com.vandunxg.file_processing.auth.application.query.RoleSearchQuery;

public interface JpaRoleRepositoryCustom {
  Long count(RoleSearchQuery query);

  List<RoleEntity> search(RoleSearchQuery query);
}
