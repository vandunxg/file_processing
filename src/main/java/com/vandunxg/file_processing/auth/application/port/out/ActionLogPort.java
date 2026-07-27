package com.vandunxg.file_processing.auth.application.port.out;

import java.util.List;

import com.vandunxg.file_processing.auth.application.query.ActionLogSearchQuery;
import com.vandunxg.file_processing.auth.domain.model.ActionLog;

public interface ActionLogPort {

  void record(ActionLog log);

  Long count(ActionLogSearchQuery query);

  List<ActionLog> search(ActionLogSearchQuery query);
}
