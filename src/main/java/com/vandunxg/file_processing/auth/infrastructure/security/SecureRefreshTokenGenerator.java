package com.vandunxg.file_processing.auth.infrastructure.security;

import java.security.SecureRandom;
import java.util.Base64;

import com.vandunxg.file_processing.auth.application.capability.RefreshTokenGenerator;
import org.springframework.stereotype.Component;

@Component
public class SecureRefreshTokenGenerator implements RefreshTokenGenerator {

  private static final int RAW_BYTES = 32;
  private final SecureRandom secureRandom = new SecureRandom();

  @Override
  public String generate() {
    byte[] bytes = new byte[RAW_BYTES];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
