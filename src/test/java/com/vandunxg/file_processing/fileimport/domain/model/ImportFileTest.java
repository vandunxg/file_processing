package com.vandunxg.file_processing.fileimport.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ImportFileTest {

  private static final String CHECKSUM = "a".repeat(64);
  private static final Instant RETENTION_DEADLINE = Instant.parse("2026-08-27T00:00:00Z");

  @Test
  void registerCreatesStoredFileMetadata() {
    UUID ownerId = UUID.randomUUID();

    ImportFile file =
        ImportFile.register(
            ownerId,
            " customers.csv ",
            "imports/2026/07/file.csv",
            CHECKSUM,
            123L,
            "text/csv",
            RETENTION_DEADLINE);

    assertThat(file.getId()).isNotNull();
    assertThat(file.getOwnerId()).isEqualTo(ownerId);
    assertThat(file.getOriginalFilename()).isEqualTo("customers.csv");
    assertThat(file.getStorageKey()).isEqualTo("imports/2026/07/file.csv");
    assertThat(file.getChecksumSha256()).isEqualTo(CHECKSUM);
    assertThat(file.getSizeBytes()).isEqualTo(123L);
    assertThat(file.getDetectedContentType()).isEqualTo("text/csv");
    assertThat(file.getRetentionDeadline()).isEqualTo(RETENTION_DEADLINE);
  }

  @Test
  void registerRejectsInvalidChecksum() {
    assertThatThrownBy(
            () ->
                ImportFile.register(
                    UUID.randomUUID(),
                    "customers.csv",
                    "imports/file.csv",
                    "ABC",
                    1L,
                    "text/csv",
                    RETENTION_DEADLINE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("checksum");
  }

  @Test
  void registerRejectsMissingCoreFields() {
    assertThatThrownBy(
            () ->
                ImportFile.register(
                    UUID.randomUUID(),
                    " ",
                    "imports/file.csv",
                    CHECKSUM,
                    1L,
                    "text/csv",
                    RETENTION_DEADLINE))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(
            () ->
                ImportFile.register(
                    UUID.randomUUID(),
                    "customers.csv",
                    " ",
                    CHECKSUM,
                    1L,
                    "text/csv",
                    RETENTION_DEADLINE))
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
                    CHECKSUM,
                    -1L,
                    "text/csv",
                    RETENTION_DEADLINE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("size");
  }
}
