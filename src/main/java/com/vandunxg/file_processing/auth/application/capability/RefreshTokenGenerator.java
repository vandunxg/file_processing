package com.vandunxg.file_processing.auth.application.capability;

public interface RefreshTokenGenerator {

  /** Cryptographically-random opaque refresh token, URL-safe base64. */
  String generate();
}
