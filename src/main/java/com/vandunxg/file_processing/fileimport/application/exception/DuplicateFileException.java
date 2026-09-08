package com.vandunxg.file_processing.fileimport.application.exception;

import com.vandunxg.file_processing.fileimport.application.result.DuplicateFileResult;
import lombok.Getter;

@Getter
public class DuplicateFileException extends FileImportException {

  private final DuplicateFileResult existing;

  public DuplicateFileException(DuplicateFileResult existing) {
    super(FileImportErrorCode.FILE_IMPORT_DUPLICATE_FILE);
    this.existing = existing;
  }
}
