package com.vandunxg.file_processing.auth.application.capability;

public interface PasswordHasher {

  /**
   * Returns the encoded password, e.g. {@code "{bcrypt}$2a$..."} via {@code
   * DelegatingPasswordEncoder}.
   */
  String hash(String rawPassword);

  /** Constant-time verification against an encoded password produced by {@link #hash}. */
  boolean matches(String rawPassword, String encodedPassword);

  /**
   * An encoded password no account owns, for the login path to verify against when the identifier
   * does not exist.
   *
   * <p>Rejecting an unknown identifier early would return in microseconds while a wrong password
   * costs a full bcrypt verification, and that difference is measurable from outside — it tells an
   * attacker which usernames are real. Burning the same work keeps the two paths indistinguishable.
   */
  String dummyHash();

  /**
   * Whether {@code encodedPassword} was produced with weaker parameters than the ones configured
   * now, so a successful login can transparently re-hash it at the current cost.
   */
  boolean needsRehash(String encodedPassword);
}
