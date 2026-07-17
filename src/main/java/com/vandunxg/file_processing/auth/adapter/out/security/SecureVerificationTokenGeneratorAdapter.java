package com.vandunxg.file_processing.auth.adapter.out.security;

import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.stereotype.Component;

import com.vandunxg.file_processing.auth.application.port.out.VerificationTokenGeneratorPort;

@Component
public class SecureVerificationTokenGeneratorAdapter implements VerificationTokenGeneratorPort {

  private static final int TOKEN_BYTE_LENGTH = 32;

  private final SecureRandom secureRandom = new SecureRandom();

  @Override
  public String generate() {
    byte[] randomBytes = new byte[TOKEN_BYTE_LENGTH];
    secureRandom.nextBytes(randomBytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
  }
}
