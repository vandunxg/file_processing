package com.vandunxg.file_processing.fileimport.application.capability;

import java.io.InputStream;
import java.util.Optional;

import com.vandunxg.file_processing.fileimport.application.validation.ValidatedCustomerRow;

/**
 * Streams a stored customer CSV one validated row at a time.
 *
 * <p>Orchestration needs rows, not a parser: this contract keeps the CSV library, the character
 * decoding, and the row-size limits inside infrastructure while the workflow above it stays a loop
 * over {@link Run#next()}. A run never materializes the whole file.
 *
 * <p>Both operations signal a structurally unusable file with {@link
 * com.vandunxg.file_processing.fileimport.application.exception.CsvFormatException}.
 */
public interface CustomerCsvReader {

  /** Reads far enough to prove the header and first record are usable, then stops. */
  void validateHeader(InputStream input);

  Run open(InputStream input);

  interface Run extends AutoCloseable {

    /** The next validated row, or empty at end of file. */
    Optional<ValidatedCustomerRow> next();

    @Override
    void close();
  }
}
