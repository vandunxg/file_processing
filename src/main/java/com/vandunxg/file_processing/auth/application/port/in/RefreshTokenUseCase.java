package com.vandunxg.file_processing.auth.application.port.in;

import com.vandunxg.file_processing.auth.application.command.RefreshTokenCommand;
import com.vandunxg.file_processing.auth.application.result.LoginResult;

public interface RefreshTokenUseCase {

  LoginResult refresh(RefreshTokenCommand command);
}
