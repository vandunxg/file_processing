package com.vandunxg.file_processing.auth.application.command;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginCommand {

  private String username;
  private String password;
  private String userAgent;
  private String ipAddress;
}
