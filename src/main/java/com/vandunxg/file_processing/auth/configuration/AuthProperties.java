package com.vandunxg.file_processing.auth.configuration;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(
    Password password, Register register, EmailVerification emailVerification) {

  public record Password(String encoder, int bcryptCost, int minLength, int maxLength) {}

  public record Register(int maxAttemptsPerHour) {}

  public record EmailVerification(
      Duration tokenTtl, String baseUrl, int resendMaxAttemptsPerHour) {}
}
