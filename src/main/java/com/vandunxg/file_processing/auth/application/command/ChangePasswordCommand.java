package com.vandunxg.file_processing.auth.application.command;

import java.util.UUID;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class ChangePasswordCommand {

  private final UUID userId;
  private final String currentPassword;
  private final String newPassword;
  private final String confirmPassword;
  private final String ipAddress;
}
