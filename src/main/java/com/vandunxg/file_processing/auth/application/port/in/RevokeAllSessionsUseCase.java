package com.vandunxg.file_processing.auth.application.port.in;

import com.vandunxg.file_processing.auth.application.command.RevokeAllSessionsCommand;

public interface RevokeAllSessionsUseCase {

  /**
   * Cross-cutting seam: revokes every active session for {@code command.userId}, bumps the user's
   * credential version, and invalidates the credential-version cache so all outstanding access
   * tokens fail their {@code cv} check at the resource server. Password-change flows in future
   * specs call this method to invalidate every device on password rotation.
   */
  void revokeAll(RevokeAllSessionsCommand command);
}
