package com.vandunxg.file_processing.fileimport.application.capability;

import java.io.InputStream;
import java.util.UUID;

import com.vandunxg.file_processing.fileimport.application.validation.ParsedCustomerRow;
import com.vandunxg.file_processing.fileimport.application.validation.ValidationIssue;

/**
 * Collects rejected rows for one attempt and publishes them only if that attempt succeeds.
 *
 * <p>Issues are written as they are found rather than gathered in memory, because a large file can
 * reject a large number of rows. A draft only becomes a downloadable report when {@link
 * Draft#publish()} is called; an attempt that fails or is cancelled closes without publishing, so a
 * partial report is never mistaken for the real one.
 */
public interface ErrorReportStore {

  Draft open(UUID jobId);

  InputStream openPublished(String reportKey);

  interface Draft extends AutoCloseable {

    void write(ValidationIssue issue, ParsedCustomerRow originalRow);

    /** Stores the draft and returns its key, or null when no issue was ever written. */
    String publish();

    /** Discards anything not published. */
    @Override
    void close();
  }
}
