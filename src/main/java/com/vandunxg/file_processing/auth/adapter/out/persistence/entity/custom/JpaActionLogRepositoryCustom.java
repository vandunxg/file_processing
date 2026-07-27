package com.vandunxg.file_processing.auth.adapter.out.persistence.entity.custom;

import java.util.List;

import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.ActionLogEntity;
import com.vandunxg.file_processing.auth.application.query.ActionLogSearchQuery;

public interface JpaActionLogRepositoryCustom {
  Long count(ActionLogSearchQuery query);

  List<ActionLogEntity> search(ActionLogSearchQuery query);
}
