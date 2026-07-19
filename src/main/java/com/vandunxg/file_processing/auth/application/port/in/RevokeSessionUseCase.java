package com.vandunxg.file_processing.auth.application.port.in;

import com.vandunxg.file_processing.auth.application.command.RevokeSessionCommand;

public interface RevokeSessionUseCase {

  void revoke(RevokeSessionCommand command);
}
