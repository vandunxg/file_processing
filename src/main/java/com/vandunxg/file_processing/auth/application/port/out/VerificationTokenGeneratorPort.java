package com.vandunxg.file_processing.auth.application.port.out;

public interface VerificationTokenGeneratorPort {

  /** 256-bit SecureRandom, Base64 URL-safe no-padding encoding of 32 raw bytes. */
  String generate();
}
