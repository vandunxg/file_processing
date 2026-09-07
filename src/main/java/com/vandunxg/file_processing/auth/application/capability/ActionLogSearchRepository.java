package com.vandunxg.file_processing.auth.application.capability;

import java.util.List;

import com.vandunxg.file_processing.auth.application.query.ActionLogSearchQuery;
import com.vandunxg.file_processing.auth.domain.model.ActionLog;

/** Paginated action-log read model. */
public interface ActionLogSearchRepository {

  Long count(ActionLogSearchQuery query);

  List<ActionLog> search(ActionLogSearchQuery query);
}
