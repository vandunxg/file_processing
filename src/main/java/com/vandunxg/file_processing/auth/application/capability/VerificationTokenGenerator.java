package com.vandunxg.file_processing.auth.application.capability;

public interface VerificationTokenGenerator {

  /** 256-bit SecureRandom, Base64 URL-safe no-padding encoding of 32 raw bytes. */
  String generate();
}
