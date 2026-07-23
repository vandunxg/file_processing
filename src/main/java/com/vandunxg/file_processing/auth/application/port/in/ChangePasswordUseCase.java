package com.vandunxg.file_processing.auth.application.port.in;

import com.vandunxg.file_processing.auth.application.command.ChangePasswordCommand;

public interface ChangePasswordUseCase {

  void change(ChangePasswordCommand command);

  void complete(ChangePasswordCommand command);
}
