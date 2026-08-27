package com.vandunxg.file_processing.fileimport.adapter.out.persistence.entity;

import java.time.Instant;
import java.util.UUID;

import com.vandunxg.common.models.entities.AuditableEntity;
import com.vandunxg.file_processing.fileimport.domain.model.ImportProcessingStatus;
import com.vandunxg.file_processing.fileimport.domain.model.StorageProvider;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "file_import")
@Getter
@Setter
@ToString
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false, of = "id")
public class ImportFileEntity extends AuditableEntity {

  @Id
  @Column(name = "id")
  private UUID id;

  @Column(name = "owner_id", nullable = false)
  private UUID ownerId;

  @Column(name = "original_filename", nullable = false, length = 255)
  private String originalFilename;

  @Column(name = "storage_key", nullable = false, length = 512)
  private String storageKey;

  @Column(name = "checksum_sha256", nullable = false, length = 64)
  private String checksumSha256;

  @Column(name = "size_bytes", nullable = false)
  private long sizeBytes;

  @Column(name = "detected_content_type", nullable = false, length = 100)
  private String detectedContentType;

  @Column(name = "retention_deadline", nullable = false)
  private Instant retentionDeadline;

  @Column(name = "bucket", nullable = false, length = 100)
  private String bucket;

  @Column(name = "storage_provider", nullable = false, length = 20)
  @Enumerated(EnumType.STRING)
  private StorageProvider storageProvider;

  @Enumerated(EnumType.STRING)
  @Column(name = "processing_status", nullable = false, length = 30)
  private ImportProcessingStatus processingStatus;

  @Column(name = "processed_rows", nullable = false)
  private long processedRows;

  @Column(name = "valid_rows", nullable = false)
  private long validRows;

  @Column(name = "invalid_rows", nullable = false)
  private long invalidRows;

  @Column(name = "inserted_rows", nullable = false)
  private long insertedRows;

  @Column(name = "updated_rows", nullable = false)
  private long updatedRows;

  @Column(name = "error_report_key", length = 512)
  private String errorReportKey;

  @Version
  @Column(name = "version", nullable = false)
  private Long version;
}
