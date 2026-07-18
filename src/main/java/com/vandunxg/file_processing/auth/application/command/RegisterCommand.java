package com.vandunxg.file_processing.auth.application.command;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class RegisterCommand {

  private final String username;
  private final String email;
  private final String displayName;
  private final String password;
  private final String ipAddress;
}
