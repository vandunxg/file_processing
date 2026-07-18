package com.vandunxg.file_processing.auth.configuration;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(
    Password password, Register register, EmailVerification emailVerification, Redis redis) {

  public record Password(String encoder, int bcryptCost, int minLength, int maxLength) {}

  public record Register(int maxAttemptsPerHour) {}

  public record EmailVerification(
      Duration tokenTtl, String baseUrl, int resendMaxAttemptsPerHour) {}

  public record Redis(Throttle throttle, EmailVerificationKeys emailVerification) {

    public record Throttle(String keyPrefix, Duration window) {}

    public record EmailVerificationKeys(String tokenKeyPrefix, String userKeyPrefix) {}
  }
}
