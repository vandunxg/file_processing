package com.vandunxg.file_processing.auth.application.result;

import java.time.Instant;
import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginResult {

  private String status;
  private String passwordChangeToken;
  private String tokenType;
  private String accessToken;
  private long expiresIn;
  private Instant accessTokenExpiresAt;
  private String refreshToken;
  private Long refreshExpiresIn;
  private Instant refreshTokenExpiresAt;
  private UUID sessionId;
  private UUID userId;
}
