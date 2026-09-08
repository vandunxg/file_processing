package com.vandunxg.file_processing.fileimport.application;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Operational settings the import workflow needs.
 *
 * <p>Everything here bounds resource use or paces the worker, so each value has a working default
 * and none of them may be zero or negative. Storage credentials and client tuning are
 * infrastructure concerns and live elsewhere.
 *
 * @param maxFileSize largest upload accepted
 * @param retention how long a stored original stays replayable
 * @param batchSize rows per customer upsert transaction
 * @param progressRowInterval rows to process before persisting progress
 * @param progressTimeInterval time to elapse before persisting progress
 * @param pollInterval how often a worker looks for queued work
 * @param staleHeartbeatThreshold silence after which a running job is treated as abandoned
 * @param workerThreads imports this instance may run at the same time
 */
@ConfigurationProperties(prefix = "app.file-import")
public record FileImportProperties(
    DataSize maxFileSize,
    Duration retention,
    int batchSize,
    long progressRowInterval,
    Duration progressTimeInterval,
    Duration pollInterval,
    Duration staleHeartbeatThreshold,
    int workerThreads) {

  public FileImportProperties {
    maxFileSize = maxFileSize == null ? DataSize.ofMegabytes(500) : maxFileSize;
    batchSize = batchSize == 0 ? 1_000 : batchSize;
    progressRowInterval = progressRowInterval == 0 ? 5_000 : progressRowInterval;
    progressTimeInterval =
        progressTimeInterval == null ? Duration.ofSeconds(2) : progressTimeInterval;
    pollInterval = pollInterval == null ? Duration.ofSeconds(1) : pollInterval;
    staleHeartbeatThreshold =
        staleHeartbeatThreshold == null ? Duration.ofMinutes(5) : staleHeartbeatThreshold;
    workerThreads = workerThreads == 0 ? 1 : workerThreads;

    requirePositive(retention, "File import retention");
    requirePositive(progressTimeInterval, "Progress time interval");
    requirePositive(pollInterval, "Poll interval");
    requirePositive(staleHeartbeatThreshold, "Stale heartbeat threshold");
    if (maxFileSize.toBytes() <= 0) {
      throw new IllegalArgumentException("Max file size must be positive");
    }
    requirePositive(batchSize, "Batch size");
    requirePositive(progressRowInterval, "Progress row interval");
    requirePositive(workerThreads, "Worker threads");
  }

  private static void requirePositive(Duration value, String name) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  private static void requirePositive(long value, String name) {
    if (value <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }
}
