package com.vandunxg.file_processing.fileimport.application.service;

import java.io.InputStream;
import java.util.UUID;

import com.vandunxg.file_processing.fileimport.application.port.out.FileImportRepositoryPort;
import com.vandunxg.file_processing.fileimport.application.port.out.ObjectStoragePort;
import com.vandunxg.file_processing.fileimport.domain.exception.FileImportErrorCode;
import com.vandunxg.file_processing.fileimport.domain.exception.FileImportException;
import com.vandunxg.file_processing.fileimport.domain.model.ImportProcessingStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ErrorReportDownloadService {

  private final FileImportRepositoryPort fileImportRepositoryPort;
  private final ObjectStoragePort objectStoragePort;

  public InputStream download(UUID fileImportId, UUID ownerId) {
    var fileImport =
        fileImportRepositoryPort
            .findByIdAndOwnerId(fileImportId, ownerId)
            .orElseThrow(() -> new FileImportException(FileImportErrorCode.FILE_IMPORT_NOT_FOUND));
    if (fileImport.getProcessingStatus() != ImportProcessingStatus.COMPLETED_WITH_ERRORS
        || fileImport.getErrorReportKey() == null) {
      throw new FileImportException(FileImportErrorCode.REPORT_NOT_AVAILABLE);
    }
    return objectStoragePort.open(fileImport.getErrorReportKey());
  }
}
