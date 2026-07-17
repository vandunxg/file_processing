package com.vandunxg.file_processing.auth.application.port.in;

import com.vandunxg.file_processing.auth.application.command.VerifyEmailCommand;
import com.vandunxg.file_processing.auth.application.result.RegisterResult;

public interface VerifyEmailUseCase {

  RegisterResult verifyEmail(VerifyEmailCommand command);
}
