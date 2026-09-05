package com.vandunxg.file_processing.auth.application.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;

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

  /**
   * Reads each bundle file directly instead of through {@link java.util.ResourceBundle}. A resource
   * bundle resolves a missing key against its parent, so {@code messages_en} would inherit anything
   * present in the base {@code messages} file and this guard could never fail for English — which
   * is exactly the case it exists to catch. RULE.md section 7.3 requires the key in every file.
   */
  @Test
  void everyErrorNameHasAMessageInEveryBundleFile() {
    Map<String, Properties> bundles =
        Map.of(
            "messages.properties", load("/i18n/messages.properties"),
            "messages_en.properties", load("/i18n/messages_en.properties"),
            "messages_vi.properties", load("/i18n/messages_vi.properties"));

    for (AuthErrorCode error : AuthErrorCode.values()) {
      bundles.forEach(
          (file, messages) ->
              assertThat(messages.getProperty(error.getName()))
                  .as("%s missing from %s", error.getName(), file)
                  .isNotNull());
    }
  }

  private static Properties load(String resource) {
    Properties properties = new Properties();
    try (InputStream stream = AuthErrorCodeTest.class.getResourceAsStream(resource)) {
      assertThat(stream).as(resource).isNotNull();
      properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
    return properties;
  }

  @Test
  void everyDomainRuleMapsToAnErrorCode() {
    for (AuthRule rule : AuthRule.values()) {
      assertThat(AuthErrorCode.from(rule)).as(rule.name()).isNotNull();
    }
  }

  /**
   * Full snapshot of the published numeric contract, not a sample. The three-code version of this
   * guard happened to pin none of the codes that actually moved during the DDD migration, so it
   * passed while AUTH_PASSWORD_POLICY_VIOLATION went 40001 -> 42202 and
   * AUTH_PASSWORD_CONFIRMATION_MISMATCH went 40003 -> 42203.
   *
   * <p>Those two renumberings are deliberate and accepted: RULE.md section 7.2 forbids renumbering
   * a published code without an API compatibility decision, and the decision is recorded in
   * docs/specs/auth-module-requirements.md under the error catalog. Both old codes carried a 400
   * prefix while returning 422, which section 7.1 does not allow. Every other code kept its number.
   *
   * <p>Changing any entry below is an API break: update the client contract and the spec first.
   */
  @Test
  void publishedCodesAreNotRenumbered() {
    Map<AuthErrorCode, Integer> published =
        Map.ofEntries(
            Map.entry(AuthErrorCode.AUTH_PASSWORD_POLICY_VIOLATION, 42202),
            Map.entry(AuthErrorCode.AUTH_PASSWORD_CONFIRMATION_MISMATCH, 42203),
            Map.entry(AuthErrorCode.AUTH_PASSWORD_SAME_AS_CURRENT, 40004),
            Map.entry(AuthErrorCode.AUTH_PASSWORD_RESET_TOKEN_INVALID, 40005),
            Map.entry(AuthErrorCode.AUTH_PASSWORD_RESET_NOT_ALLOWED, 40006),
            Map.entry(AuthErrorCode.AUTH_USERNAME_ALREADY_EXISTS, 40902),
            Map.entry(AuthErrorCode.AUTH_EMAIL_ALREADY_EXISTS, 40903),
            Map.entry(AuthErrorCode.AUTH_RATE_LIMITED, 42901),
            Map.entry(AuthErrorCode.AUTH_EMAIL_VERIFICATION_TOKEN_INVALID, 40002),
            Map.entry(AuthErrorCode.USER_ALREADY_VERIFIED, 40907),
            Map.entry(AuthErrorCode.USER_NOT_FOUND, 40401),
            Map.entry(AuthErrorCode.AUTH_CURRENT_PASSWORD_INVALID, 40007),
            Map.entry(AuthErrorCode.AUTH_PASSWORD_REUSE_NOT_ALLOWED, 40904),
            Map.entry(AuthErrorCode.AUTH_INVALID_CREDENTIALS, 40101),
            Map.entry(AuthErrorCode.AUTH_ACCOUNT_LOCKED, 40301),
            Map.entry(AuthErrorCode.AUTH_EMAIL_VERIFICATION_REQUIRED, 40302),
            Map.entry(AuthErrorCode.AUTH_CSRF_TOKEN_INVALID, 40303),
            Map.entry(AuthErrorCode.AUTH_REFRESH_TOKEN_INVALID, 40102),
            Map.entry(AuthErrorCode.AUTH_REFRESH_TOKEN_REUSED, 40103),
            Map.entry(AuthErrorCode.AUTH_PASSWORD_CHANGE_TOKEN_INVALID, 40106),
            Map.entry(AuthErrorCode.AUTH_SESSION_NOT_FOUND, 40402),
            Map.entry(AuthErrorCode.ROLE_INVALID, 42201),
            Map.entry(AuthErrorCode.ROLE_NOT_FOUND, 40403),
            Map.entry(AuthErrorCode.ROLE_CODE_ALREADY_EXISTS, 40908),
            Map.entry(AuthErrorCode.ROLE_INHERITANCE_CYCLE, 40909),
            Map.entry(AuthErrorCode.ROLE_STILL_ASSIGNED, 40910),
            Map.entry(AuthErrorCode.ROLE_IS_CONST, 40911),
            Map.entry(AuthErrorCode.AUTH_LAST_ACTIVE_ADMIN, 40912),
            Map.entry(AuthErrorCode.ROLE_IS_ACTIVE, 40913));

    assertThat(published).hasSize(AuthErrorCode.values().length);
    published.forEach(
        (error, code) -> assertThat(error.getCode()).as(error.getName()).isEqualTo(code));
  }
}
