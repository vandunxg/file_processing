package com.vandunxg.file_processing.auth.application.port.out;

import java.util.Optional;
import java.util.UUID;

public interface CredentialVersionCachePort {

  Optional<Integer> get(UUID userId);

  void put(UUID userId, int credentialVersion);

  void invalidate(UUID userId);
}
