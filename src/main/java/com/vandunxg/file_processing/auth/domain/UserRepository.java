package com.vandunxg.file_processing.auth.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.vandunxg.file_processing.auth.domain.model.User;
import com.vandunxg.file_processing.auth.domain.model.UserRole;

/**
 * Aggregate repository for {@link User}. Role assignments ({@link UserRole}) are part of the user
 * aggregate, so they are persisted through this contract instead of a child repository.
 */
public interface UserRepository {

  /** Includes soft-deleted users: bootstrap is allowed only on a truly empty system. */
  boolean existsAny();

  boolean existsByNormalizedUsername(String normalizedUsername);

  boolean existsByNormalizedEmail(String normalizedEmail);

  /** Returns the fully hydrated aggregate, including roles. */
  Optional<User> findById(UUID id);

  Optional<User> findByIdForUpdate(UUID id);

  List<User> findAll();

  long countActiveAdmins();

  /**
   * Returns the fully hydrated aggregate, including roles. '@' present -> email lookup, else
   * username.
   */
  Optional<User> findByNormalizedIdentifier(String normalizedIdentifier);

  /** Narrow read for hot paths (e.g. per-request JWT validation) that only need this one field. */
  Optional<Integer> findCredentialVersionById(UUID id);

  /** MUST flush immediately (saveAndFlush) so unique-constraint races surface here. */
  User save(User user);

  /** Adds one role assignment to the user aggregate. */
  UserRole assignRole(UserRole userRole);

  /** Soft-deletes the user's current role assignments and installs {@code roleIds} instead. */
  void replaceRoles(UUID userId, Set<UUID> roleIds, Instant now);
}
