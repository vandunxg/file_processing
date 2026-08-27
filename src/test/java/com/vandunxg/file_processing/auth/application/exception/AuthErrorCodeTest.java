package com.vandunxg.file_processing.auth.application.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;

import com.vandunxg.file_processing.auth.domain.exception.AuthRule;
import org.junit.jupiter.api.Test;

/** Guards the error contract required by RULE.md section 7: prefixes, codes, and i18n keys. */
class AuthErrorCodeTest {

  private static final List<String> ALLOWED_PREFIXES = List.of("AUTH_", "USER_", "ROLE_");

  @Test
  void everyErrorNameStartsWithAModulePrefix() {
    assertThat(Arrays.stream(AuthErrorCode.values()).map(AuthErrorCode::getName).toList())
        .allSatisfy(
            name ->
                assertThat(ALLOWED_PREFIXES)
                    .anySatisfy(prefix -> assertThat(name).startsWith(prefix)));
  }

  @Test
  void numericBusinessCodesAreUnique() {
    assertThat(Arrays.stream(AuthErrorCode.values()).map(AuthErrorCode::getCode).toList())
        .doesNotHaveDuplicates();
  }

  @Test
  void numericBusinessCodesKeepTheHttpStatusPrefix() {
    for (AuthErrorCode error : AuthErrorCode.values()) {
      assertThat(String.valueOf(error.getCode()))
          .as(error.getName())
          .startsWith(String.valueOf(error.getStatus()));
    }
  }

  @Test
  void everyErrorNameHasAnEnglishAndVietnameseMessage() {
    ResourceBundle english = ResourceBundle.getBundle("i18n.messages", Locale.ENGLISH);
    ResourceBundle vietnamese =
        ResourceBundle.getBundle("i18n.messages", Locale.forLanguageTag("vi"));

    for (AuthErrorCode error : AuthErrorCode.values()) {
      assertThat(english.containsKey(error.getName())).as(error.getName()).isTrue();
      assertThat(vietnamese.containsKey(error.getName())).as(error.getName()).isTrue();
    }
  }

  @Test
  void everyDomainRuleMapsToAnErrorCode() {
    for (AuthRule rule : AuthRule.values()) {
      assertThat(AuthErrorCode.from(rule)).as(rule.name()).isNotNull();
    }
  }

  @Test
  void publishedCodesAreNotRenumbered() {
    assertThat(AuthErrorCode.USER_ALREADY_VERIFIED.getCode()).isEqualTo(40907);
    assertThat(AuthErrorCode.AUTH_INVALID_CREDENTIALS.getCode()).isEqualTo(40101);
    assertThat(AuthErrorCode.ROLE_IS_ACTIVE.getCode()).isEqualTo(40913);
  }
}
