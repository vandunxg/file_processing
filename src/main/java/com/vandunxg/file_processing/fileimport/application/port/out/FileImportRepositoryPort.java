package com.vandunxg.file_processing.fileimport.application.port.out;

import com.vandunxg.file_processing.fileimport.domain.model.FileImport;

public interface FileImportRepositoryPort {

  FileImport save(FileImport fileImport);
}
