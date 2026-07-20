package com.vandunxg.file_processing.auth.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.vandunxg.file_processing.auth.adapter.in.web.AuthController;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.request.ChangePasswordRequest;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.security.SecurityScheme;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

class OpenApiConfigurationTest {

  @Test
  void declaresBearerAuthenticationAsTheDefaultApiSecurityRequirement() throws Exception {
    Class<?> configuration = configurationClass();

    assertThat(configuration).isNotNull();
    OpenAPI openApi =
        (OpenAPI)
            configuration
                .getDeclaredMethod("openApi")
                .invoke(configuration.getDeclaredConstructor().newInstance());

    assertThat(openApi.getInfo().getTitle()).isEqualTo("File Processing Auth API");
    assertThat(openApi.getComponents().getSecuritySchemes())
        .containsKey("bearerAuth")
        .extractingByKey("bearerAuth")
        .extracting(
            SecurityScheme::getType, SecurityScheme::getScheme, SecurityScheme::getBearerFormat)
        .containsExactly(SecurityScheme.Type.HTTP, "bearer", "JWT");
    assertThat(openApi.getSecurity())
        .singleElement()
        .extracting(requirement -> requirement.get("bearerAuth"))
        .isEqualTo(java.util.List.of());
  }

  @Test
  void keepsBearerSecurityForPasswordChangeCompletion() throws Exception {
    assertThat(
            AuthController.class
                .getMethod(
                    "completePasswordChange",
                    ChangePasswordRequest.class,
                    String.class,
                    HttpServletRequest.class)
                .getAnnotation(SecurityRequirements.class))
        .isNull();
  }

  private static Class<?> configurationClass() {
    try {
      return Class.forName("com.vandunxg.file_processing.auth.configuration.OpenApiConfiguration");
    } catch (ClassNotFoundException ignored) {
      return null;
    }
  }
}
