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

    FileImport file =
        FileImport.register(
            ownerId,
            " customers.csv ",
            "imports/2026/07/file.csv",
            CHECKSUM,
            123L,
            "text/csv",
            RETENTION_DEADLINE,
            "file-processing",
            StorageProvider.R2);

    assertThat(file.getId()).isNotNull();
    assertThat(file.getOwnerId()).isEqualTo(ownerId);
    assertThat(file.getOriginalFilename()).isEqualTo("customers.csv");
    assertThat(file.getStorageKey()).isEqualTo("imports/2026/07/file.csv");
    assertThat(file.getChecksumSha256()).isEqualTo(CHECKSUM);
    assertThat(file.getSizeBytes()).isEqualTo(123L);
    assertThat(file.getDetectedContentType()).isEqualTo("text/csv");
    assertThat(file.getRetentionDeadline()).isEqualTo(RETENTION_DEADLINE);
    assertThat(file.getProcessingStatus()).isEqualTo(ImportProcessingStatus.PROCESSING);
  }

  @Test
  void completeRecordsFinalCountersAndReportAvailability() {
    FileImport file =
        FileImport.register(
            UUID.randomUUID(),
            "customers.csv",
            "imports/file.csv",
            CHECKSUM,
            123L,
            "text/csv",
            RETENTION_DEADLINE,
            "file-processing",
            StorageProvider.R2);

    file.complete(3, 1, 2, 1, "reports/file.csv");

    assertThat(file.getProcessingStatus()).isEqualTo(ImportProcessingStatus.COMPLETED_WITH_ERRORS);
    assertThat(file.getProcessedRows()).isEqualTo(4);
    assertThat(file.getValidRows()).isEqualTo(3);
    assertThat(file.getInvalidRows()).isEqualTo(1);
    assertThat(file.getInsertedRows()).isEqualTo(2);
    assertThat(file.getUpdatedRows()).isEqualTo(1);
    assertThat(file.getErrorReportKey()).isEqualTo("reports/file.csv");
  }

  @Test
  void completeRejectsErrorsWithoutAReportAndFailSetsTerminalStatus() {
    FileImport file =
        FileImport.register(
            UUID.randomUUID(),
            "customers.csv",
            "imports/file.csv",
            CHECKSUM,
            123L,
            "text/csv",
            RETENTION_DEADLINE,
            "file-processing",
            StorageProvider.R2);

    assertThatThrownBy(() -> file.complete(0, 1, 0, 0, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("report");

    file.complete(0, 1, 0, 0, "reports/file.csv");
    file.fail();

    assertThat(file.getProcessingStatus()).isEqualTo(ImportProcessingStatus.FAILED);
    assertThat(file.getErrorReportKey()).isNull();
  }

  @Test
  void registerRejectsInvalidChecksum() {
    assertThatThrownBy(
            () ->
                FileImport.register(
                    UUID.randomUUID(),
                    "customers.csv",
                    "imports/file.csv",
                    "ABC",
                    1L,
                    "text/csv",
                    RETENTION_DEADLINE,
                    "file-processing",
                    StorageProvider.R2))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("checksum");
  }

  @Test
  void registerRejectsMissingCoreFields() {
    assertThatThrownBy(
            () ->
                FileImport.register(
                    UUID.randomUUID(),
                    " ",
                    "imports/file.csv",
                    CHECKSUM,
                    1L,
                    "text/csv",
                    RETENTION_DEADLINE,
                    "file-processing",
                    StorageProvider.R2))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(
            () ->
                FileImport.register(
                    UUID.randomUUID(),
                    "customers.csv",
                    " ",
                    CHECKSUM,
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
                FileImport.register(
                    UUID.randomUUID(),
                    "customers.csv",
                    "imports/file.csv",
                    CHECKSUM,
                    -1L,
                    "text/csv",
                    RETENTION_DEADLINE,
                    "file-processing",
                    StorageProvider.R2))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("size");
  }
}
