package com.vandunxg.file_processing.auth.application.command;

import java.util.UUID;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LogoutCommand {

  private UUID sessionId;
  private UUID userId;
  private String ipAddress;
}
