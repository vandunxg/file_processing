package com.vandunxg.file_processing.auth.application.command;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

@Getter
@Setter
@Builder
public class LogoutCommand {

  private UUID sessionId;
  private UUID userId;
  private String ipAddress;
}
