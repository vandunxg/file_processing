package com.vandunxg.file_processing.fileimport.application.exception;

import com.vandunxg.common.models.error.ResponseError;
import com.vandunxg.file_processing.fileimport.domain.exception.ProcessingJobRule;

/**
 * Response contract for the file-import module.
 *
 * <p>Numeric codes follow the repository rule of {@code {httpStatus}{sequence}} and are allocated
 * in a range that does not collide with any other module's catalog.
 */
public enum FileImportErrorCode implements ResponseError {
  FILE_IMPORT_FILE_REQUIRED(40051, "A file is required", 400),
  FILE_IMPORT_ONLY_ONE_FILE_ALLOWED(40052, "Exactly one file is allowed", 400),
  FILE_IMPORT_EMPTY_FILE(40053, "File must not be empty", 400),
  FILE_IMPORT_NOT_FOUND(40451, "File import was not found", 404),
  FILE_IMPORT_DUPLICATE_FILE(40951, "An identical file has already been uploaded", 409),
  FILE_IMPORT_ORIGINAL_FILE_EXPIRED(41051, "The original file is no longer available", 410),
  FILE_IMPORT_FILE_TOO_LARGE(41351, "File exceeds the maximum allowed size", 413),
  FILE_IMPORT_UNSUPPORTED_FILE_TYPE(41551, "Only CSV files are supported", 415),
  FILE_IMPORT_INVALID_CSV_HEADER(42251, "The CSV header is invalid", 422),
  FILE_IMPORT_MALFORMED_CSV(42252, "The CSV file is malformed", 422),
  FILE_IMPORT_STORAGE_UNAVAILABLE(50351, "File storage is unavailable", 503),

  PROCESSING_JOB_NOT_FOUND(40452, "Processing job was not found", 404),
  PROCESSING_JOB_NOT_CANCELLABLE(40952, "Processing job can no longer be cancelled", 409),
  PROCESSING_JOB_NOT_RETRYABLE(40953, "Processing job cannot be retried", 409),
  PROCESSING_JOB_RETRY_LIMIT_EXCEEDED(40954, "Retry limit for this job has been reached", 409),
  PROCESSING_JOB_REPORT_NOT_AVAILABLE(40955, "A final error report is not available", 409),
  PROCESSING_JOB_CONFLICT(40956, "Processing job changed while the request was in flight", 409),
  PROCESSING_JOB_REPORT_EXPIRED(41052, "The error report is no longer available", 410);

  private final Integer code;
  private final String message;
  private final int status;

  FileImportErrorCode(Integer code, String message, int status) {
    this.code = code;
    this.message = message;
    this.status = status;
  }

  /**
   * Translates a domain rule violation into the response contract.
   *
   * <p>Rules a caller can act on get their own code. The rest describe invariants only a worker can
   * break -- recording progress on an unclaimed job, for instance -- and surface as a generic
   * conflict rather than leaking internal lifecycle detail to the client.
   */
  public static FileImportErrorCode from(ProcessingJobRule rule) {
    return switch (rule) {
      case ONLY_QUEUED_JOB_CAN_BE_CANCELLED, JOB_NOT_CANCELLABLE -> PROCESSING_JOB_NOT_CANCELLABLE;
      case JOB_NOT_RETRYABLE -> PROCESSING_JOB_NOT_RETRYABLE;
      case RETRY_LIMIT_EXCEEDED -> PROCESSING_JOB_RETRY_LIMIT_EXCEEDED;
      case REPORT_AVAILABILITY_MISMATCH -> PROCESSING_JOB_REPORT_NOT_AVAILABLE;
      case ONLY_QUEUED_JOB_CAN_BE_CLAIMED,
          ONLY_PROCESSING_JOB_CAN_COMPLETE,
          ONLY_RUNNING_JOB_CAN_RECORD_PROGRESS,
          ONLY_RUNNING_JOB_CAN_FAIL,
          CANCELLATION_MUST_BE_REQUESTED_FIRST,
          PROGRESS_CANNOT_DECREASE,
          INVALID_COUNTERS ->
          PROCESSING_JOB_CONFLICT;
    };
  }

  @Override
  public Integer getCode() {
    return code;
  }

  @Override
  public String getName() {
    return name();
  }

  @Override
  public String getMessage() {
    return message;
  }

  @Override
  public int getStatus() {
    return status;
  }
}
