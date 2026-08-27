package com.vandunxg.file_processing.auth.application.capability;

import java.util.Optional;
import java.util.UUID;

public interface CredentialVersionCache {

  Optional<Integer> get(UUID userId);

  void put(UUID userId, int credentialVersion);

  void invalidate(UUID userId);
}
