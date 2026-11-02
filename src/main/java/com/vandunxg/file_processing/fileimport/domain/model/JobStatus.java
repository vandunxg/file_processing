package com.vandunxg.file_processing.fileimport.domain.model;

public enum JobStatus {
  QUEUED,
  PROCESSING,
  CANCELLATION_REQUESTED,
  COMPLETED,
  COMPLETED_WITH_ERRORS,
  FAILED,
  CANCELLED
}
