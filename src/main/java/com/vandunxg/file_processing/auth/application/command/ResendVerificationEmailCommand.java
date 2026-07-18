package com.vandunxg.file_processing.auth.application.command;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ResendVerificationEmailCommand {

  private final String identifier;
  private final String ipAddress;
}
