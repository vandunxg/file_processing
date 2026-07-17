package com.vandunxg.file_processing.auth.application.port.in;

import com.vandunxg.file_processing.auth.application.command.RegisterCommand;
import com.vandunxg.file_processing.auth.application.result.RegisterResult;

public interface RegisterUseCase {

  RegisterResult register(RegisterCommand command);
}
