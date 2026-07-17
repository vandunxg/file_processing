package com.vandunxg.file_processing.auth.application.command;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class VerifyEmailCommand {

  private final String token;
}
