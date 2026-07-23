package com.vandunxg.file_processing.auth.application.port.out;

import com.vandunxg.file_processing.auth.domain.model.ActionLog;

public interface ActionLogPort {

  void record(ActionLog log);
}
