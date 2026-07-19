package com.vandunxg.file_processing.auth.application.port.in;

import com.vandunxg.file_processing.auth.application.command.LoginCommand;
import com.vandunxg.file_processing.auth.application.result.LoginResult;

public interface LoginUseCase {

  LoginResult login(LoginCommand command);
}
