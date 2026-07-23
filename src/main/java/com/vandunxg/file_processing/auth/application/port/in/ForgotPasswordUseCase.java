package com.vandunxg.file_processing.auth.application.port.in;

import com.vandunxg.file_processing.auth.application.command.ForgotPasswordCommand;

public interface ForgotPasswordUseCase {

  void request(ForgotPasswordCommand command);
}
