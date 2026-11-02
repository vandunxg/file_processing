package com.vandunxg.file_processing.fileimport.infrastructure.persistence.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.vandunxg.file_processing.fileimport.domain.model.AttemptStatus;
import com.vandunxg.file_processing.fileimport.domain.model.AttemptTrigger;
import com.vandunxg.file_processing.fileimport.domain.model.JobStatus;
import com.vandunxg.file_processing.fileimport.domain.model.ProcessingAttempt;
import com.vandunxg.file_processing.fileimport.domain.model.ProcessingJob;
import com.vandunxg.file_processing.fileimport.domain.model.RowCounters;
import org.junit.jupiter.api.Test;

/**
 * The repository writes by merging the mapped entity over the stored row, so any field this mapper
 * drops is written back as {@code null} and the stored value is lost. A round trip comparing every
 * field is therefore the guard: it fails when a field is added to either side and not carried
 * across, which column-by-column assertions would not keep up with.
 *
 * <p>Every value below is distinct on purpose, so a field landing in the wrong place shows up. The
 * totals deliberately do not add up: reconstitution replays a stored row without judging it, and a
 * round trip that only worked for arithmetically valid rows would not prove that.
 */
class ProcessingJobPersistenceMapperTest {

  private static final Instant NOW = Instant.parse("2026-09-09T10:00:00Z");

  private final ProcessingJobPersistenceMapper mapper = new ProcessingJobPersistenceMapperImpl();

  @Test
  void everyStoredFieldSurvivesTheRoundTripToTheRowAndBack() {
    ProcessingJob stored = fullyPopulatedJob();

    ProcessingJob roundTripped = mapper.toDomain(mapper.toEntity(stored));

    assertThat(roundTripped).usingRecursiveComparison().isEqualTo(stored);
  }

  @Test
  void aRetiredJobIsMappedAsRetiredRatherThanResurrected() {
    ProcessingJob retired = fullyPopulatedJob();
    assertThat(retired.getDeletedAt()).isNotNull();

    assertThat(mapper.toEntity(retired).getDeletedAt()).isEqualTo(retired.getDeletedAt());
  }

  @Test
  void theLockVersionIsCarriedToTheRowSoAStaleWriteCanBeRejected() {
    ProcessingJob stored = fullyPopulatedJob();

    assertThat(mapper.toEntity(stored).getVersion()).isEqualTo(stored.getVersion());
  }

  @Test
  void theCreationAuditIsCarriedBackBecauseAnUpdateWouldNotRestoreIt() {
    ProcessingJob stored = fullyPopulatedJob();

    var row = mapper.toEntity(stored);

    assertThat(row.getCreatedAt()).isEqualTo(stored.getCreatedAt());
    assertThat(row.getCreatedBy()).isEqualTo(stored.getCreatedBy());
  }

  private static ProcessingJob fullyPopulatedJob() {
    UUID jobId = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    ProcessingAttempt attempt =
        ProcessingAttempt.reconstitute(
            UUID.fromString("00000000-0000-0000-0000-0000000000b1"),
            jobId,
            1,
            AttemptTrigger.USER_RETRY,
            AttemptStatus.FAILED,
            NOW.plusSeconds(1),
            NOW.plusSeconds(2),
            RowCounters.builder()
                .processedRows(11)
                .validRows(12)
                .invalidRows(13)
                .insertedRows(14)
                .updatedRows(15)
                .build(),
            "ATTEMPT_ERROR",
            "attempt failed",
            NOW.plusSeconds(3),
            "attempt-creator",
            NOW.plusSeconds(4),
            "attempt-modifier",
            NOW.plusSeconds(5));

    return ProcessingJob.reconstitute(
        jobId,
        UUID.fromString("00000000-0000-0000-0000-0000000000c1"),
        UUID.fromString("00000000-0000-0000-0000-0000000000d1"),
        JobStatus.COMPLETED_WITH_ERRORS,
        RowCounters.builder()
            .processedRows(21)
            .validRows(22)
            .invalidRows(23)
            .insertedRows(24)
            .updatedRows(25)
            .build(),
        26L,
        27,
        28,
        NOW.plusSeconds(6),
        NOW.plusSeconds(7),
        NOW.plusSeconds(8),
        "reports/job.csv",
        "JOB_ERROR",
        "job failed",
        AttemptTrigger.ADMIN_RETRY,
        NOW.plusSeconds(9),
        31L,
        List.of(attempt),
        "job-creator",
        NOW.plusSeconds(10),
        "job-modifier",
        NOW.plusSeconds(11));
  }
}
