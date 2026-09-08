package com.vandunxg.file_processing.fileimport.application.service;

import java.io.InputStream;
import java.util.UUID;

import com.vandunxg.file_processing.fileimport.application.capability.FileStorage;
import com.vandunxg.file_processing.fileimport.application.exception.FileImportErrorCode;
import com.vandunxg.file_processing.fileimport.application.exception.FileImportException;
import com.vandunxg.file_processing.fileimport.domain.ImportFileRepository;
import com.vandunxg.file_processing.fileimport.domain.model.ImportProcessingStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ErrorReportDownloadService {

  private final ImportFileRepository importFileRepository;
  private final FileStorage fileStorage;

  public InputStream download(UUID fileImportId, UUID ownerId) {
    var fileImport =
        importFileRepository
            .findByIdAndOwnerId(fileImportId, ownerId)
            .orElseThrow(() -> new FileImportException(FileImportErrorCode.FILE_IMPORT_NOT_FOUND));
    if (fileImport.getProcessingStatus() != ImportProcessingStatus.COMPLETED_WITH_ERRORS
        || fileImport.getErrorReportKey() == null) {
      throw new FileImportException(FileImportErrorCode.REPORT_NOT_AVAILABLE);
    }
    return fileStorage.open(fileImport.getErrorReportKey());
  }
}
