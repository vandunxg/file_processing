package com.vandunxg.file_processing.fileimport.infrastructure.metrics;

import com.vandunxg.file_processing.fileimport.application.capability.FileImportMetrics;
import com.vandunxg.file_processing.fileimport.domain.model.JobStatus;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Emits only bounded result and operation tags; never a job, file, owner, or error message. */
@Component
@RequiredArgsConstructor
public class MicrometerFileImportMetrics implements FileImportMetrics {

  private final MeterRegistry meterRegistry;

  @Override
  public void uploadAccepted(long bytes) {
    meterRegistry.counter("file_upload_total", "result", "accepted").increment();
    meterRegistry.counter("file_upload_bytes_total").increment(bytes);
  }

  @Override
  public void uploadDuplicate() {
    meterRegistry.counter("file_upload_total", "result", "duplicate").increment();
    meterRegistry.counter("duplicate_upload_total").increment();
  }

  @Override
  public void jobFinished(JobStatus status, long validRows, long invalidRows) {
    meterRegistry.counter("processing_job_total", "status", status.name()).increment();
    if (validRows > 0) {
      meterRegistry.counter("processing_rows_total", "result", "valid").increment(validRows);
    }
    if (invalidRows > 0) {
      meterRegistry.counter("processing_rows_total", "result", "invalid").increment(invalidRows);
    }
  }

  @Override
  public void cancellation(String result) {
    meterRegistry.counter("job_cancel_total", "result", result).increment();
  }

  @Override
  public void timeout(String operation) {
    meterRegistry.counter("processing_timeout_total", "operation", operation).increment();
  }
}
