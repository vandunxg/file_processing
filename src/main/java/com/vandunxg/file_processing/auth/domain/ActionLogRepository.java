package com.vandunxg.file_processing.auth.domain;

import com.vandunxg.file_processing.auth.domain.model.ActionLog;

/** Append-only store for {@link ActionLog}. Paginated reads live in the application layer. */
public interface ActionLogRepository {

  void record(ActionLog log);
}
