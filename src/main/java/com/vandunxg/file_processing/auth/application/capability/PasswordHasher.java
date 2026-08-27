package com.vandunxg.file_processing.auth.application.capability;

public interface PasswordHasher {

  /**
   * Returns the encoded password, e.g. {@code "{bcrypt}$2a$..."} via {@code
   * DelegatingPasswordEncoder}.
   */
  String hash(String rawPassword);

  /** Constant-time verification against an encoded password produced by {@link #hash}. */
  boolean matches(String rawPassword, String encodedPassword);
}
