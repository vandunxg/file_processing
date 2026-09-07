package com.vandunxg.file_processing.auth.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;

import com.vandunxg.file_processing.auth.application.command.LoginCommand;
import com.vandunxg.file_processing.auth.application.command.RefreshTokenCommand;
import com.vandunxg.file_processing.auth.application.exception.AuthException;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;

/**
 * Two auth paths write precisely when they reject: a failed login records the attempt that arms the
 * lock-out, and a reused refresh token revokes the session family and burns the credential version.
 * {@link AuthException} is unchecked, so without an explicit rule Spring rolls both back — the
 * lock-out never persists and the attacker's session survives.
 *
 * <p>Nothing else in the suite can catch that: the service unit tests drive after-commit callbacks
 * by hand, so they observe a commit that would never happen in production. This guards the
 * annotation itself.
 */
class RejectionPathsCommitTest {

  @Test
  void loginKeepsWhatItWroteWhenItRejectsTheCredentials() throws NoSuchMethodException {
    assertNoRollbackForAuthException(
        AuthenticationCommandService.class.getMethod("login", LoginCommand.class));
  }

  @Test
  void refreshKeepsTheReuseCascadeWhenItRejectsTheToken() throws NoSuchMethodException {
    assertNoRollbackForAuthException(
        SessionCommandService.class.getMethod("refresh", RefreshTokenCommand.class));
  }

  private static void assertNoRollbackForAuthException(Method method) {
    Transactional transactional = method.getAnnotation(Transactional.class);
    assertThat(transactional).as("%s must be transactional", method.getName()).isNotNull();
    assertThat(transactional.noRollbackFor())
        .as(
            "%s writes on its rejecting paths; without noRollbackFor those writes are discarded",
            method.getName())
        .contains(AuthException.class);
  }
}
