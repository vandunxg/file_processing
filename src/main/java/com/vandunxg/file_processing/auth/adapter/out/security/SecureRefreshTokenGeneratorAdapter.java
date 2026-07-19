package com.vandunxg.file_processing.auth.adapter.out.security;

import java.security.SecureRandom;
import java.util.Base64;

import com.vandunxg.file_processing.auth.application.port.out.RefreshTokenGeneratorPort;
import org.springframework.stereotype.Component;

@Component
public class SecureRefreshTokenGeneratorAdapter implements RefreshTokenGeneratorPort {

  private static final int RAW_BYTES = 32;
  private final SecureRandom secureRandom = new SecureRandom();

  @Override
  public String generate() {
    byte[] bytes = new byte[RAW_BYTES];
    secureRandom.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
