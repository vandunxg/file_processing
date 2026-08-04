package com.vandunxg.file_processing.fileimport.application.result;

import java.time.Instant;
import java.util.UUID;

import com.vandunxg.file_processing.fileimport.domain.model.FileImport;

public record UploadFileResult(
    UUID id,
    String originalFilename,
    long sizeBytes,
    String contentType,
    String checksumSha256,
    Instant retentionDeadline) {

  public static UploadFileResult from(FileImport fileImport) {
    return new UploadFileResult(
        fileImport.getId(),
        fileImport.getOriginalFilename(),
        fileImport.getSizeBytes(),
        fileImport.getDetectedContentType(),
        fileImport.getChecksumSha256(),
        fileImport.getRetentionDeadline());
  }
}
