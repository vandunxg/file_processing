package com.vandunxg.file_processing.auth.infrastructure.security;

import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.vandunxg.file_processing.auth.application.AuthProperties;
import com.vandunxg.file_processing.auth.application.capability.PasswordHasher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Wraps a {@link DelegatingPasswordEncoder} so the stored password is prefixed with the encoding
 * scheme (e.g. {@code "{bcrypt}$2a$..."}), matching {@code PasswordEncoderFactories}-style
 * upgrade-friendly storage.
 */
@Slf4j(topic = "AUTH-PASSWORD")
@Component
public class BcryptPasswordHasher implements PasswordHasher {

  private static final String BCRYPT_ID = "bcrypt";

  /** {@code $2a$<cost>$} — the cost is the two digits after the second marker. */
  private static final Pattern BCRYPT_COST = Pattern.compile("^\\{bcrypt}\\$2[aby]\\$(\\d{2})\\$");

  private final PasswordEncoder passwordEncoder;
  private final int bcryptCost;
  private final String dummyHash;

  public BcryptPasswordHasher(AuthProperties authProperties) {
    this.bcryptCost = authProperties.password().bcryptCost();
    this.passwordEncoder =
        new DelegatingPasswordEncoder(
            BCRYPT_ID, Map.of(BCRYPT_ID, new BCryptPasswordEncoder(this.bcryptCost)));
    // Encoded once at startup, from a value nobody can present, so the unknown-identifier path
    // costs the same bcrypt work as a wrong password without holding a real credential.
    this.dummyHash = this.passwordEncoder.encode(UUID.randomUUID().toString());
  }

  @Override
  public String hash(String rawPassword) {
    if (rawPassword == null || rawPassword.isBlank()) {
      // Programming-error guard: callers (RegisterService via PasswordPolicy) must never reach
      // this adapter with a blank password. This is not a business rule to enforce here.
      log.warn("[hash] rejected null or blank raw password input");
      throw new IllegalArgumentException("rawPassword must not be null or blank");
    }
    return passwordEncoder.encode(rawPassword);
  }

  @Override
  public boolean matches(String rawPassword, String encodedPassword) {
    if (rawPassword == null || encodedPassword == null) {
      return false;
    }
    return passwordEncoder.matches(rawPassword, encodedPassword);
  }

  @Override
  public String dummyHash() {
    return dummyHash;
  }

  @Override
  public boolean needsRehash(String encodedPassword) {
    if (encodedPassword == null) {
      return false;
    }
    Matcher matcher = BCRYPT_COST.matcher(encodedPassword);
    if (!matcher.find()) {
      // Not a bcrypt hash we recognise (a legacy scheme, or a future one): re-hashing it at the
      // current cost is the safe reading.
      return true;
    }
    return Integer.parseInt(matcher.group(1)) < bcryptCost;
  }
}
