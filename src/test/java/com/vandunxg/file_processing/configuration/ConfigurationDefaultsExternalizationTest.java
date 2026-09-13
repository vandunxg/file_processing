package com.vandunxg.file_processing.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class ConfigurationDefaultsExternalizationTest {

  @Test
  void keeps_application_defaults_in_yaml_not_java() throws IOException {
    Path applicationYaml = Path.of("src/main/resources/application.yml");

    assertThat(applicationYaml).exists();
    String yaml = Files.readString(applicationYaml);
    String scheduler =
        Files.readString(
            Path.of(
                "src/main/java/com/vandunxg/file_processing/fileimport/infrastructure/scheduling/ProcessingJobScheduler.java"));
    String fileImportProperties =
        Files.readString(
            Path.of(
                "src/main/java/com/vandunxg/file_processing/fileimport/application/FileImportProperties.java"));
    String authProperties =
        Files.readString(
            Path.of(
                "src/main/java/com/vandunxg/file_processing/auth/application/AuthProperties.java"));

    assertThat(yaml)
        .contains(
            "poll-interval:",
            "recovery-interval:",
            "staging-sweep-interval:",
            "retention-cleanup-interval:",
            "max-file-size:",
            "batch-size:",
            "progress-row-interval:",
            "progress-time-interval:",
            "stale-heartbeat-threshold:",
            "worker-threads:",
            "password-reset:",
            "cleanup:",
            "cookie-secure:");
    assertThat(scheduler).doesNotContain("${app.file-import.poll-interval:");
    assertThat(scheduler).doesNotContain("${app.file-import.recovery-interval:");
    assertThat(scheduler).doesNotContain("${app.file-import.retention-cleanup-interval:");
    assertThat(fileImportProperties).doesNotContain(" == null ?", " == 0 ?");
    assertThat(authProperties).doesNotContain("public AuthProperties(", "public Refresh(Duration");
  }

  @Test
  void enables_rabbitmq_for_the_dev_profile() throws IOException {
    String devYaml = Files.readString(Path.of("src/main/resources/application-dev.yml"));

    assertThat(devYaml)
        .contains(
            "rabbitmq:\n    enabled: true",
            "host: ${RABBITMQ_HOST:localhost}",
            "port: ${RABBITMQ_PORT:5672}",
            "username: ${RABBITMQ_USERNAME:rabbitmq_user}",
            "password: ${RABBITMQ_PASSWORD:rabbitmq_password}");
  }
}
