package com.vandunxg.file_processing.auth.application.command;

import java.util.UUID;

import com.vandunxg.file_processing.auth.domain.model.RevocationReason;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RevokeAllSessionsCommand {

  private UUID userId;
  private RevocationReason reason;
  private String ipAddress;
}
