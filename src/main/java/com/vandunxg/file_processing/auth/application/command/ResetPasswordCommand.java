package com.vandunxg.file_processing.auth.application.command;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ResetPasswordCommand {

  private final String token;
  private final String newPassword;
  private final String confirmPassword;
  private final String ipAddress;
}
