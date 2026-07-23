package com.vandunxg.file_processing.auth.application.port.in;

import com.vandunxg.file_processing.auth.application.command.ResetPasswordCommand;

public interface ResetPasswordUseCase {

  void reset(ResetPasswordCommand command);
}
