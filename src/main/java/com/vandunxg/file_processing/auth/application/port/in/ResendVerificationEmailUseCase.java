package com.vandunxg.file_processing.auth.application.port.in;

import com.vandunxg.file_processing.auth.application.command.ResendVerificationEmailCommand;

public interface ResendVerificationEmailUseCase {

  void resend(ResendVerificationEmailCommand command);
}
