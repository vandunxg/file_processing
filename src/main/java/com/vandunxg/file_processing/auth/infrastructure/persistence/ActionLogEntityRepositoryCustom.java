package com.vandunxg.file_processing.auth.infrastructure.persistence;

import java.util.List;

import com.vandunxg.file_processing.auth.application.query.ActionLogSearchQuery;
import com.vandunxg.file_processing.auth.infrastructure.persistence.entity.ActionLogEntity;

public interface ActionLogEntityRepositoryCustom {
  Long count(ActionLogSearchQuery query);

  List<ActionLogEntity> search(ActionLogSearchQuery query);
}
