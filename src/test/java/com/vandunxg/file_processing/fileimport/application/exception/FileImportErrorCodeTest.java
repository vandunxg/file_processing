package com.vandunxg.file_processing.fileimport.application.exception;

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

import com.vandunxg.file_processing.fileimport.domain.exception.ProcessingJobRule;
import org.junit.jupiter.api.Test;

/** Guards the error contract required by RULE.md section 7: prefixes, codes, and i18n keys. */
class FileImportErrorCodeTest {

  private static final List<String> ALLOWED_PREFIXES = List.of("FILE_IMPORT_", "PROCESSING_JOB_");

  @Test
  void everyErrorNameStartsWithAModulePrefix() {
    assertThat(
            Arrays.stream(FileImportErrorCode.values()).map(FileImportErrorCode::getName).toList())
        .allSatisfy(
            name ->
                assertThat(ALLOWED_PREFIXES)
                    .anySatisfy(prefix -> assertThat(name).startsWith(prefix)));
  }

  @Test
  void numericBusinessCodesAreUnique() {
    assertThat(
            Arrays.stream(FileImportErrorCode.values()).map(FileImportErrorCode::getCode).toList())
        .doesNotHaveDuplicates();
  }

  @Test
  void numericBusinessCodesKeepTheHttpStatusPrefix() {
    for (FileImportErrorCode error : FileImportErrorCode.values()) {
      assertThat(String.valueOf(error.getCode()))
          .as(error.getName())
          .startsWith(String.valueOf(error.getStatus()));
    }
  }

  /**
   * Reads each bundle file directly rather than through {@link java.util.ResourceBundle}, which
   * would resolve a missing key against its parent and hide exactly the gap this guards.
   */
  @Test
  void everyErrorNameHasAMessageInEveryBundleFile() {
    Map<String, Properties> bundles =
        Map.of(
            "messages.properties", load("/i18n/messages.properties"),
            "messages_en.properties", load("/i18n/messages_en.properties"),
            "messages_vi.properties", load("/i18n/messages_vi.properties"));

    for (FileImportErrorCode error : FileImportErrorCode.values()) {
      bundles.forEach(
          (file, messages) ->
              assertThat(messages.getProperty(error.getName()))
                  .as("%s missing from %s", error.getName(), file)
                  .isNotNull());
    }
  }

  @Test
  void everyDomainRuleMapsToAnErrorCode() {
    for (ProcessingJobRule rule : ProcessingJobRule.values()) {
      assertThat(FileImportErrorCode.from(rule)).as(rule.name()).isNotNull();
    }
  }

  /** The module's codes must not collide with any other module's catalog. */
  @Test
  void numericBusinessCodesDoNotCollideWithTheAuthCatalog() {
    List<Integer> authCodes =
        Arrays.stream(
                com.vandunxg.file_processing.auth.application.exception.AuthErrorCode.values())
            .map(com.vandunxg.file_processing.auth.application.exception.AuthErrorCode::getCode)
            .toList();

    assertThat(
            Arrays.stream(FileImportErrorCode.values()).map(FileImportErrorCode::getCode).toList())
        .doesNotContainAnyElementsOf(authCodes);
  }

  private static Properties load(String resource) {
    Properties properties = new Properties();
    try (InputStream stream = FileImportErrorCodeTest.class.getResourceAsStream(resource)) {
      assertThat(stream).as(resource).isNotNull();
      properties.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
    return properties;
  }
}
