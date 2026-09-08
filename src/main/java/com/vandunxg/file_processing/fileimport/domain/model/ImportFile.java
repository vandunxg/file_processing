package com.vandunxg.file_processing.fileimport.domain.model;

import java.time.Instant;
import java.util.UUID;

import com.vandunxg.common.models.domain.AuditableDomain;
import com.vandunxg.common.utils.IdUtils;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/** Immutable metadata for an original object that has been safely stored. */
@Getter
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true, of = "id")
public class ImportFile extends AuditableDomain {

  private final UUID id;
  private final UUID ownerId;
  private final String originalFilename;
  private final String storageKey;
  private final FileChecksum checksum;
  private final long sizeBytes;
  private final String detectedContentType;
  private final Instant retentionDeadline;
  private final String bucket;
  private final StorageProvider storageProvider;
  private final Long version;

  private ImportFile(
      UUID id,
      UUID ownerId,
      String originalFilename,
      String storageKey,
      FileChecksum checksum,
      long sizeBytes,
      String detectedContentType,
      Instant retentionDeadline,
      String bucket,
      StorageProvider storageProvider,
      Long version) {
    this.id = id;
    this.ownerId = ownerId;
    this.originalFilename = originalFilename;
    this.storageKey = storageKey;
    this.checksum = checksum;
    this.sizeBytes = sizeBytes;
    this.detectedContentType = detectedContentType;
    this.retentionDeadline = retentionDeadline;
    this.bucket = bucket;
    this.storageProvider = storageProvider;
    this.version = version;
  }

  public static ImportFile register(
      UUID ownerId,
      String originalFilename,
      String storageKey,
      FileChecksum checksum,
      long sizeBytes,
      String detectedContentType,
      Instant retentionDeadline,
      String bucket,
      StorageProvider storageProvider) {
    if (ownerId == null
        || isBlank(originalFilename)
        || isBlank(storageKey)
        || checksum == null
        || isBlank(detectedContentType)
        || retentionDeadline == null
        || isBlank(bucket)
        || storageProvider == null) {
      throw new IllegalArgumentException("Import file metadata is required");
    }
    if (sizeBytes < 0) {
      throw new IllegalArgumentException("Import file size must be non-negative");
    }
    return new ImportFile(
        IdUtils.nextId(),
        ownerId,
        originalFilename.trim(),
        storageKey.trim(),
        checksum,
        sizeBytes,
        detectedContentType.trim(),
        retentionDeadline,
        bucket.trim(),
        storageProvider,
        null);
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
