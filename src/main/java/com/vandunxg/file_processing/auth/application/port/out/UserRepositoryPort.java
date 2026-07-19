package com.vandunxg.file_processing.auth.application.port.out;

import java.util.Optional;
import java.util.UUID;

import com.vandunxg.file_processing.auth.domain.model.User;

public interface UserRepositoryPort {

  boolean existsByNormalizedUsername(String normalizedUsername);

  boolean existsByNormalizedEmail(String normalizedEmail);

  /** Returns the fully hydrated aggregate, including roles. */
  Optional<User> findById(UUID id);

  /**
   * Returns the fully hydrated aggregate, including roles. '@' present -> email lookup, else
   * username.
   */
  Optional<User> findByNormalizedIdentifier(String normalizedIdentifier);

  /** MUST flush immediately (saveAndFlush) so unique-constraint races surface here. */
  User save(User user);
}
