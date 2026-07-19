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
public class SessionResult {

  private UUID sessionId;
  private String userAgent;
  private Instant createdAt;
  private Instant lastUsedAt;
  private Instant expiresAt;
  private boolean current;
}
