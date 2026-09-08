package com.vandunxg.file_processing.fileimport.application.result;

/**
 * What a caller can do with a job right now.
 *
 * <p>Sent with the detail view so a client does not have to reimplement the state machine to decide
 * which buttons to show, and cannot get it wrong in a way that produces a conflict the user sees.
 */
public enum ProcessingJobAction {
  CANCEL,
  RETRY,
  DOWNLOAD_ERROR_REPORT
}
