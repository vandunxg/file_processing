package com.vandunxg.file_processing.fileimport.application.capability;

import com.vandunxg.file_processing.fileimport.domain.model.JobStatus;

/** Low-cardinality operational measurements for the file-import workflow. */
public interface FileImportMetrics {

  void uploadAccepted(long bytes);

  void uploadDuplicate();

  void jobFinished(JobStatus status, long validRows, long invalidRows);

  void cancellation(String result);

  void timeout(String operation);
}
