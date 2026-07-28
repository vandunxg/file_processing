package com.vandunxg.file_processing.auth.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.stream.Stream;

import com.vandunxg.file_processing.auth.configuration.security.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

class CurrentUserSessionControllerContractTest {

  @Test
  void mapsSelfServiceSessionsOnlyUnderTheCanonicalMePath() throws Exception {
    Class<?> controller = currentUserSessionControllerClass();

    assertThat(controller).isNotNull();
    if (controller == null) {
      return;
    }
    assertThat(controller.getAnnotation(RequestMapping.class).value())
        .containsExactly("${app.api.prefix}/${app.api.version}/me/sessions");
    assertThat(
            controller
                .getMethod("listSessions", AuthenticatedUser.class)
                .getAnnotation(GetMapping.class)
                .value())
        .isEmpty();
    assertThat(
            controller
                .getMethod(
                    "revokeSession",
                    java.util.UUID.class,
                    AuthenticatedUser.class,
                    HttpServletRequest.class)
                .getAnnotation(DeleteMapping.class)
                .value())
        .containsExactly("/{sessionId}");
    assertThat(
            controller
                .getMethod(
                    "revokeAll",
                    AuthenticatedUser.class,
                    HttpServletRequest.class,
                    HttpServletResponse.class)
                .getAnnotation(PostMapping.class)
                .value())
        .containsExactly("/revoke-all");
    assertThat(authSessionMappings()).isEmpty();
  }

  @Test
  void requiresTheSelfServiceSessionPermissions() throws Exception {
    Class<?> controller = currentUserSessionControllerClass();

    assertThat(controller).isNotNull();
    if (controller == null) {
      return;
    }
    assertPermission(
        controller.getMethod("listSessions", AuthenticatedUser.class), "session:self_read");
    assertPermission(
        controller.getMethod(
            "revokeSession",
            java.util.UUID.class,
            AuthenticatedUser.class,
            HttpServletRequest.class),
        "session:self_delete");
    assertPermission(
        controller.getMethod(
            "revokeAll",
            AuthenticatedUser.class,
            HttpServletRequest.class,
            HttpServletResponse.class),
        "session:self_delete");
  }

  private static void assertPermission(Method method, String permission) {
    PreAuthorize authorization = method.getAnnotation(PreAuthorize.class);

    assertThat(authorization).isNotNull();
    assertThat(authorization.value()).isEqualTo("hasPermission(null, '" + permission + "')");
  }

  private static Stream<String> authSessionMappings() {
    return Arrays.stream(AuthController.class.getDeclaredMethods())
        .map(method -> AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class))
        .filter(java.util.Objects::nonNull)
        .flatMap(mapping -> Arrays.stream(mapping.value()))
        .filter(path -> path.startsWith("/sessions"));
  }

  private static Class<?> currentUserSessionControllerClass() {
    try {
      return Class.forName(
          "com.vandunxg.file_processing.auth.adapter.in.web.CurrentUserSessionController");
    } catch (ClassNotFoundException ignored) {
      return null;
    }
  }
}
