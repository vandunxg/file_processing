package com.vandunxg.file_processing.fileimport.application.result;

import java.time.Instant;
import java.util.UUID;

import com.vandunxg.file_processing.fileimport.domain.model.FileImport;
import com.vandunxg.file_processing.fileimport.domain.model.ImportProcessingStatus;

public record UploadFileResult(
    UUID id,
    String originalFilename,
    long sizeBytes,
    String contentType,
    String checksumSha256,
    Instant retentionDeadline,
    ImportProcessingStatus processingStatus,
    long processedRows,
    long validRows,
    long invalidRows,
    long insertedRows,
    long updatedRows,
    boolean errorReportAvailable) {

  public static UploadFileResult from(FileImport fileImport) {
    return new UploadFileResult(
        fileImport.getId(),
        fileImport.getOriginalFilename(),
        fileImport.getSizeBytes(),
        fileImport.getDetectedContentType(),
        fileImport.getChecksumSha256(),
        fileImport.getRetentionDeadline(),
        fileImport.getProcessingStatus(),
        fileImport.getProcessedRows(),
        fileImport.getValidRows(),
        fileImport.getInvalidRows(),
        fileImport.getInsertedRows(),
        fileImport.getUpdatedRows(),
        fileImport.getErrorReportKey() != null);
  }
}
