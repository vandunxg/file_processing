package com.vandunxg.file_processing.fileimport.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.vandunxg.file_processing.fileimport.application.capability.FileStorage;
import com.vandunxg.file_processing.fileimport.domain.ImportFileRepository;
import com.vandunxg.file_processing.fileimport.domain.ProcessingJobRepository;
import com.vandunxg.file_processing.fileimport.domain.model.FileChecksum;
import com.vandunxg.file_processing.fileimport.domain.model.ImportFile;
import com.vandunxg.file_processing.fileimport.domain.model.JobStatus;
import com.vandunxg.file_processing.fileimport.domain.model.ProcessingJob;
import com.vandunxg.file_processing.fileimport.domain.model.StorageProvider;
import com.vandunxg.file_processing.testsupport.AuthIntegrationTestBase;
import com.vandunxg.file_processing.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves the import really is bounded in memory at the scale the system promises.
 *
 * <p>Off by default and never part of {@code verify}: it writes hundreds of megabytes to a temp
 * file and imports every row, which takes minutes. Run it with the {@code load-test} profile, which
 * caps the JVM at the 512 MB demo heap -- the cap is the assertion. A run that finishes has
 * streamed the file; a run that buffers it dies with an {@code OutOfMemoryError} instead of quietly
 * passing.
 *
 * <pre>
 * ./mvnw -Pload-test verify
 * ./mvnw -Pload-test -DloadTest.rows=1000000 -DloadTest.addressPadding=440 verify
 * </pre>
 *
 * <p>No fixture is committed: the file is generated for the run and deleted afterwards.
 */
@PostgresIntegrationTest
@Import(LargeImportLoadIT.StreamedFileConfiguration.class)
@EnabledIfSystemProperty(
    named = "loadTest",
    matches = "true",
    disabledReason = "Load verification is opt-in; run it with -Pload-test")
class LargeImportLoadIT extends AuthIntegrationTestBase {

  private static final Logger log = LoggerFactory.getLogger(LargeImportLoadIT.class);

  private static final String HEADER = "external_id,full_name,email,phone,date_of_birth,address\n";

  /** Row count and row width, so a run can approach either documented cap. */
  private static final long ROWS = Long.getLong("loadTest.rows", 1_000_000L);

  private static final int ADDRESS_PADDING = Integer.getInteger("loadTest.addressPadding", 0);

  @Autowired private ProcessingJobRunner runner;
  @Autowired private ProcessingJobRepository jobs;
  @Autowired private ImportFileRepository files;
  @Autowired private StreamedFileStorage storage;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private TransactionTemplate transactionTemplate;

  private Path generated;

  @BeforeEach
  void reset() {
    transactionTemplate.executeWithoutResult(
        status -> {
          jdbcTemplate.update("DELETE FROM customers");
          jdbcTemplate.update("DELETE FROM processing_attempt");
          jdbcTemplate.update("DELETE FROM processing_job");
          jdbcTemplate.update("DELETE FROM file_import");
        });
  }

  @AfterEach
  void deleteGeneratedFile() throws IOException {
    if (generated != null) {
      Files.deleteIfExists(generated);
    }
  }

  @Test
  void importsAWholeLargeFileWithinTheDemoHeap() throws IOException {
    generated = generateCsv(ROWS);
    long sizeBytes = Files.size(generated);
    storage.serve(generated);
    UUID jobId = queue(sizeBytes);
    log.info("[load] rows={} sizeBytes={} heapMax={}", ROWS, sizeBytes, heapMax());

    Instant startedAt = Instant.now();
    assertThat(runner.runNextJob()).isTrue();
    Duration elapsed = Duration.between(startedAt, Instant.now());

    ProcessingJob job = jobs.findById(jobId).orElseThrow();
    assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED);
    assertThat(job.getProcessedRows()).isEqualTo(ROWS);
    assertThat(job.getValidRows()).isEqualTo(ROWS);
    assertThat(job.getInsertedRows()).isEqualTo(ROWS);
    assertThat(job.getInvalidRows()).isZero();
    assertThat(job.getTotalRows()).isEqualTo(ROWS);
    assertThat(job.getProgressPercent()).isEqualTo(100);
    assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM customers", Long.class))
        .isEqualTo(ROWS);
    log.info(
        "[load] completed rows={} sizeBytes={} elapsed={}s rows/s={}",
        ROWS,
        sizeBytes,
        elapsed.toSeconds(),
        elapsed.isZero() ? ROWS : ROWS / Math.max(1, elapsed.toSeconds()));
  }

  /** Writes the file a row at a time, so generating it is bounded too. */
  private static Path generateCsv(long rows) throws IOException {
    Path file = Files.createTempFile("file-import-load-", ".csv");
    String address = "1 Main St" + " ".repeat(Math.max(0, ADDRESS_PADDING));
    try (BufferedWriter writer =
        Files.newBufferedWriter(
            file, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING)) {
      writer.write(HEADER);
      for (long row = 1; row <= rows; row++) {
        writer.write("CUS_");
        writer.write(Long.toString(row));
        writer.write(",Nguyen Van A,cus_");
        writer.write(Long.toString(row));
        writer.write("@example.com,0912345678,2000-01-02,");
        writer.write(address);
        writer.write('\n');
      }
    }
    return file;
  }

  private UUID queue(long sizeBytes) {
    UUID ownerId = UUID.randomUUID();
    ImportFile file =
        files.save(
            ImportFile.register(
                ownerId,
                "customers.csv",
                "imports/" + UUID.randomUUID(),
                FileChecksum.of(UUID.randomUUID().toString().replace("-", "").repeat(2)),
                sizeBytes,
                "text/csv",
                Instant.now().plusSeconds(3600),
                "file-processing",
                StorageProvider.R2));
    return jobs.save(ProcessingJob.queue(file.getId(), ownerId, Instant.now())).getId();
  }

  private static long heapMax() {
    return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getMax();
  }

  @TestConfiguration
  static class StreamedFileConfiguration {

    @Bean
    @Primary
    StreamedFileStorage streamedFileStorage() {
      return new StreamedFileStorage();
    }
  }

  /**
   * Serves the original straight off disk.
   *
   * <p>Unlike the in-memory stand-in the other tests use, this one never holds the file, so the
   * heap cap measures the import rather than the fixture.
   */
  static class StreamedFileStorage implements FileStorage {

    private Path served;

    void serve(Path file) {
      this.served = file;
    }

    @Override
    public StoredObject store(
        String storageKey, String contentType, long contentLength, InputStream content) {
      throw new UnsupportedOperationException("the load test registers the file itself");
    }

    @Override
    public InputStream open(String storageKey) {
      try {
        return Files.newInputStream(served);
      } catch (IOException exception) {
        throw new UncheckedIOException(exception);
      }
    }

    @Override
    public void delete(String storageKey) {
      // The test owns the temp file.
    }
  }
}
