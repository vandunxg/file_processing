package com.vandunxg.file_processing.fileimport.application.port.in;

import com.vandunxg.file_processing.fileimport.application.command.UploadFileCommand;
import com.vandunxg.file_processing.fileimport.application.result.UploadFileResult;

public interface UploadFileUseCase {
  UploadFileResult upload(UploadFileCommand command);
}
