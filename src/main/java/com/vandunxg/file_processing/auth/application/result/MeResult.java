package com.vandunxg.file_processing.auth.application.result;

import java.util.List;
import java.util.UUID;

import com.vandunxg.file_processing.auth.domain.model.UserStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MeResult {

  private UUID userId;
  private String username;
  private String email;
  private String displayName;
  private List<String> roles;
  private UserStatus status;
}
