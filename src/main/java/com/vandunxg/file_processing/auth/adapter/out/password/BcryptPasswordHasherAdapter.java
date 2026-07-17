package com.vandunxg.file_processing.auth.adapter.out.password;

import java.util.Map;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.vandunxg.file_processing.auth.application.port.out.PasswordHasherPort;
import com.vandunxg.file_processing.auth.configuration.AuthProperties;

import lombok.extern.slf4j.Slf4j;

/**
 * Wraps a {@link DelegatingPasswordEncoder} so the stored password is prefixed with the encoding
 * scheme (e.g. {@code "{bcrypt}$2a$..."}), matching {@code PasswordEncoderFactories}-style
 * upgrade-friendly storage.
 */
@Slf4j(topic = "AUTH-PASSWORD")
@Component
public class BcryptPasswordHasherAdapter implements PasswordHasherPort {

  private static final String BCRYPT_ID = "bcrypt";

  private final PasswordEncoder passwordEncoder;

  public BcryptPasswordHasherAdapter(AuthProperties authProperties) {
    this.passwordEncoder =
        new DelegatingPasswordEncoder(
            BCRYPT_ID,
            Map.of(BCRYPT_ID, new BCryptPasswordEncoder(authProperties.password().bcryptCost())));
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
}
