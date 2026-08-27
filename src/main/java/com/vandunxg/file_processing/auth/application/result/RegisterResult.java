package com.vandunxg.file_processing.auth.application.result;

import java.util.UUID;

import com.vandunxg.file_processing.auth.domain.model.UserStatus;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public class RegisterResult {

  private final UUID id;
  private final String username;
  private final String email;
  private final String displayName;
  private final UserStatus status;
}
