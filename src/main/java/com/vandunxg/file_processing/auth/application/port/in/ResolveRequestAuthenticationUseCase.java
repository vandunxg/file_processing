package com.vandunxg.file_processing.auth.application.port.in;

import java.util.UUID;

import com.vandunxg.file_processing.auth.application.result.RequestAuthenticationResult;

public interface ResolveRequestAuthenticationUseCase {

  RequestAuthenticationResult resolve(UUID userId);
}
