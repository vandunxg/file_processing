package com.vandunxg.file_processing.fileimport.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ImportFileTest {

  private static final String CHECKSUM = "a".repeat(64);
  private static final Instant RETENTION_DEADLINE = Instant.parse("2026-08-27T00:00:00Z");

  @Test
  void registerCreatesOnlyImmutableStoredFileMetadata() {
    UUID ownerId = UUID.randomUUID();

    ImportFile file =
        ImportFile.register(
            ownerId,
            " customers.csv ",
            "imports/2026/07/file.csv",
            FileChecksum.of(CHECKSUM),
            123L,
            "text/csv",
            RETENTION_DEADLINE,
            "file-processing",
            StorageProvider.R2);

    assertThat(file.getId()).isNotNull();
    assertThat(file.getOwnerId()).isEqualTo(ownerId);
    assertThat(file.getOriginalFilename()).isEqualTo("customers.csv");
    assertThat(file.getStorageKey()).isEqualTo("imports/2026/07/file.csv");
    assertThat(file.getChecksum().value()).isEqualTo(CHECKSUM);
    assertThat(file.getSizeBytes()).isEqualTo(123L);
    assertThat(file.getDetectedContentType()).isEqualTo("text/csv");
    assertThat(file.getRetentionDeadline()).isEqualTo(RETENTION_DEADLINE);
    assertThat(ImportFile.class.getDeclaredFields())
        .extracting(field -> field.getName())
        .doesNotContain(
            "processingStatus",
            "processedRows",
            "validRows",
            "invalidRows",
            "insertedRows",
            "updatedRows",
            "errorReportKey");
  }

  @Test
  void registerRejectsMissingCoreFields() {
    assertThatThrownBy(
            () ->
                ImportFile.register(
                    UUID.randomUUID(),
                    " ",
                    "imports/file.csv",
                    FileChecksum.of(CHECKSUM),
                    1L,
                    "text/csv",
                    RETENTION_DEADLINE,
                    "file-processing",
                    StorageProvider.R2))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(
            () ->
                ImportFile.register(
                    UUID.randomUUID(),
                    "customers.csv",
                    " ",
                    FileChecksum.of(CHECKSUM),
                    1L,
                    "text/csv",
                    RETENTION_DEADLINE,
                    "file-processing",
                    StorageProvider.R2))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void registerRejectsNegativeSize() {
    assertThatThrownBy(
            () ->
                ImportFile.register(
                    UUID.randomUUID(),
                    "customers.csv",
                    "imports/file.csv",
                    FileChecksum.of(CHECKSUM),
                    -1L,
                    "text/csv",
                    RETENTION_DEADLINE,
                    "file-processing",
                    StorageProvider.R2))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("size");
  }

  @Test
  void exposesNoPublicConstructorOrBuilderThatCanBypassRegistrationInvariants() {
    assertThat(ImportFile.class.getConstructors()).isEmpty();
    assertThat(Arrays.stream(ImportFile.class.getDeclaredMethods()).map(method -> method.getName()))
        .doesNotContain("builder");
  }
}
