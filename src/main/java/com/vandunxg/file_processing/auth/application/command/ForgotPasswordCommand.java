package com.vandunxg.file_processing.auth.application.command;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ForgotPasswordCommand {

  private final String identifier;
  private final String ipAddress;
}
