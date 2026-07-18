package com.vandunxg.file_processing.auth.application.port.out;

public interface PasswordHasherPort {

  /**
   * Returns the encoded password, e.g. {@code "{bcrypt}$2a$..."} via {@code
   * DelegatingPasswordEncoder}.
   */
  String hash(String rawPassword);
}
