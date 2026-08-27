package com.vandunxg.file_processing.auth.application.capability;

import com.vandunxg.file_processing.auth.domain.model.ActionLog;

public interface ActionLogEventPublisher {

  void publish(ActionLog actionLog);
}
