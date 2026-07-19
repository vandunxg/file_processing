package com.vandunxg.file_processing.auth.application.port.out;

public interface RefreshTokenGeneratorPort {

  /** Cryptographically-random opaque refresh token, URL-safe base64. */
  String generate();
}
