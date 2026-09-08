package com.vandunxg.file_processing.fileimport.infrastructure.csv;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import com.vandunxg.file_processing.fileimport.application.capability.ErrorReportStore;
import com.vandunxg.file_processing.fileimport.application.capability.FileStorage;
import com.vandunxg.file_processing.fileimport.application.validation.ParsedCustomerRow;
import com.vandunxg.file_processing.fileimport.application.validation.ValidationIssue;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Spools rejected rows to a local file and uploads that file only when the attempt publishes.
 *
 * <p>A run can reject far more rows than fit in memory, so issues go straight to disk as they
 * arrive. Uploading only on publish is what keeps a failed or cancelled attempt from leaving a
 * half-written report in the bucket where a caller could download it as final.
 */
@Component
@RequiredArgsConstructor
@Slf4j(topic = "ERROR-REPORT-STORE")
public class StreamingErrorReportStore implements ErrorReportStore {

  private final FileStorage fileStorage;

  @Override
  public Draft open(UUID jobId) {
    try {
      Path spool = Files.createTempFile("customer-import-" + jobId + "-", ".csv");
      return new SpooledDraft(jobId, spool, fileStorage);
    } catch (IOException exception) {
      throw new UncheckedIOException("Unable to open the error report spool", exception);
    }
  }

  @Override
  public InputStream openPublished(String reportKey) {
    return fileStorage.open(reportKey);
  }

  @Override
  public void discard(String reportKey) {
    try {
      fileStorage.delete(reportKey);
    } catch (RuntimeException exception) {
      // Best effort: failing to tidy up must not turn a cancellation into an error the caller sees.
      log.warn("[report] could not discard unreferenced report key={}", reportKey, exception);
    }
  }

  @RequiredArgsConstructor
  private static final class SpooledDraft implements Draft {

    private final UUID jobId;
    private final Path spool;
    private final FileStorage fileStorage;

    private CsvErrorReportWriter writer;
    private long issues;

    @Override
    public void write(ValidationIssue issue, ParsedCustomerRow originalRow) {
      writer().write(issue, originalRow);
      issues++;
    }

    @Override
    public String publish() {
      if (issues == 0) {
        return null;
      }
      closeWriter();
      String reportKey = "reports/" + jobId + ".csv";
      try (InputStream content = Files.newInputStream(spool)) {
        fileStorage.store(reportKey, "text/csv", Files.size(spool), content);
      } catch (IOException exception) {
        throw new UncheckedIOException("Unable to publish the error report", exception);
      }
      return reportKey;
    }

    @Override
    public void close() {
      closeWriter();
      try {
        Files.deleteIfExists(spool);
      } catch (IOException exception) {
        log.warn("[report] could not delete spool jobId={}", jobId, exception);
      }
    }

    /** Created on first use so a run with no rejected rows never touches the disk. */
    private CsvErrorReportWriter writer() {
      if (writer == null) {
        try {
          writer = new CsvErrorReportWriter(spool);
        } catch (IOException exception) {
          throw new UncheckedIOException("Unable to write the error report", exception);
        }
      }
      return writer;
    }

    private void closeWriter() {
      if (writer == null) {
        return;
      }
      try {
        writer.close();
      } catch (IOException exception) {
        throw new UncheckedIOException("Unable to close the error report", exception);
      }
      writer = null;
    }
  }
}
