package com.vandunxg.file_processing.auth.application.port.in;

import com.vandunxg.file_processing.auth.application.command.LogoutCommand;

public interface LogoutUseCase {

  void logout(LogoutCommand command);
}
