package com.vandunxg.file_processing.auth.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.vandunxg.file_processing.auth.configuration.security.AuthenticatedUser;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RequestMapping;

class CurrentUserControllerContractTest {

  @Test
  void mapsTheCurrentUserProfileToTheCanonicalMePath() throws Exception {
    Class<?> controller = currentUserControllerClass();

    assertThat(controller).isNotNull();
    RequestMapping mapping = controller.getAnnotation(RequestMapping.class);

    assertThat(mapping.value()).containsExactly("${app.api.prefix}/${app.api.version}/me");
  }

  @Test
  void deniesCallersWithoutTheUserSelfReadPermission() throws Exception {
    PreAuthorize authorization =
        currentUserControllerClass()
            .getMethod("me", AuthenticatedUser.class)
            .getAnnotation(PreAuthorize.class);

    assertThat(authorization).isNotNull();
    assertThat(authorization.value()).isEqualTo("hasPermission(null, 'user:self_read')");
  }

  private static Class<?> currentUserControllerClass() {
    try {
      return Class.forName(
          "com.vandunxg.file_processing.auth.adapter.in.web.CurrentUserController");
    } catch (ClassNotFoundException ignored) {
      return null;
    }
  }
}
