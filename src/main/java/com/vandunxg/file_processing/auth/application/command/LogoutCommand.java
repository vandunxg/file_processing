package com.vandunxg.file_processing.auth.application.command;

import java.util.UUID;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Builder
public class LogoutCommand {

  private UUID sessionId;
  private UUID userId;
  private String ipAddress;
}
