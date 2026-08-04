package com.vandunxg.file_processing.fileimport.domain.model;

import java.time.Instant;
import java.util.UUID;

import com.vandunxg.common.models.domain.AuditableDomain;
import com.vandunxg.common.utils.IdUtils;
import lombok.*;
import lombok.experimental.SuperBuilder;

@Getter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Setter(AccessLevel.PRIVATE)
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true, of = "id")
public class FileImport extends AuditableDomain {

  private UUID id;
  private UUID ownerId;
  private String originalFilename;
  private String storageKey;
  private String checksumSha256;
  private long sizeBytes;
  private String detectedContentType;
  private Instant retentionDeadline;
  private String bucket;
  private StorageProvider storageProvider;
  private Long version;

  public static FileImport register(
      UUID ownerId,
      String originalFilename,
      String storageKey,
      String checksumSha256,
      long sizeBytes,
      String detectedContentType,
      Instant retentionDeadline,
      String bucket,
      StorageProvider storageProvider) {
    if (ownerId == null
        || isBlank(originalFilename)
        || isBlank(storageKey)
        || isBlank(detectedContentType)
        || isBlank(bucket)
        || retentionDeadline == null
        || storageProvider == null) {
      throw new IllegalArgumentException("Import file metadata is required");
    }
    if (checksumSha256 == null || !checksumSha256.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Import file checksum must be lowercase SHA-256 hex");
    }
    if (sizeBytes < 0) {
      throw new IllegalArgumentException("Import file size must be non-negative");
    }
    return FileImport.builder()
        .id(IdUtils.nextId())
        .ownerId(ownerId)
        .originalFilename(originalFilename.trim())
        .storageKey(storageKey.trim())
        .checksumSha256(checksumSha256)
        .sizeBytes(sizeBytes)
        .detectedContentType(detectedContentType.trim())
        .retentionDeadline(retentionDeadline)
        .bucket(bucket.trim())
        .storageProvider(storageProvider)
        .build();
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
