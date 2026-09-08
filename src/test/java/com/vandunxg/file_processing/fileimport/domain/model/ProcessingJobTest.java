package com.vandunxg.file_processing.fileimport.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import com.vandunxg.file_processing.fileimport.domain.exception.ProcessingJobRule;
import com.vandunxg.file_processing.fileimport.domain.exception.ProcessingJobRuleViolation;
import org.junit.jupiter.api.Test;

class ProcessingJobTest {

  private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");

  @Test
  void claimMovesQueuedJobToProcessingAndStartsInitialAttempt() {
    ProcessingJob job = ProcessingJob.queue(UUID.randomUUID(), UUID.randomUUID(), NOW);

    job.claim(NOW);

    assertThat(job.getStatus()).isEqualTo(JobStatus.PROCESSING);
    assertThat(job.getCurrentAttempt()).isOne();
    assertThat(job.getAttempts())
        .singleElement()
        .extracting(ProcessingAttempt::getTrigger, ProcessingAttempt::getStatus)
        .containsExactly(AttemptTrigger.INITIAL, AttemptStatus.RUNNING);
  }

  @Test
  void claimRejectsAJobThatIsNoLongerQueued() {
    ProcessingJob job = ProcessingJob.queue(UUID.randomUUID(), UUID.randomUUID(), NOW);
    job.claim(NOW);

    assertThatThrownBy(() -> job.claim(NOW.plusSeconds(1)))
        .isInstanceOf(ProcessingJobRuleViolation.class)
        .extracting(exception -> ((ProcessingJobRuleViolation) exception).getRule())
        .isEqualTo(ProcessingJobRule.ONLY_QUEUED_JOB_CAN_BE_CLAIMED);
  }

  @Test
  void cancelQueuedJobCompletesWithoutCreatingAnAttempt() {
    ProcessingJob job = ProcessingJob.queue(UUID.randomUUID(), UUID.randomUUID(), NOW);

    job.cancelQueued(NOW);

    assertThat(job.getStatus()).isEqualTo(JobStatus.CANCELLED);
    assertThat(job.getAttempts()).isEmpty();
  }

  @Test
  void cancelQueuedRejectsAJobAlreadyClaimedByTheWorker() {
    ProcessingJob job = ProcessingJob.queue(UUID.randomUUID(), UUID.randomUUID(), NOW);
    job.claim(NOW);

    assertThatThrownBy(() -> job.cancelQueued(NOW.plusSeconds(1)))
        .isInstanceOf(ProcessingJobRuleViolation.class)
        .extracting(exception -> ((ProcessingJobRuleViolation) exception).getRule())
        .isEqualTo(ProcessingJobRule.ONLY_QUEUED_JOB_CAN_BE_CANCELLED);
  }

  @Test
  void requestCancellationMarksProcessingJobForCooperativeStop() {
    ProcessingJob job = ProcessingJob.queue(UUID.randomUUID(), UUID.randomUUID(), NOW);
    job.claim(NOW);

    job.requestCancellation();

    assertThat(job.getStatus()).isEqualTo(JobStatus.CANCELLATION_REQUESTED);
  }

  @Test
  void recordProgressKeepsAllAttemptCountersTogether() {
    ProcessingJob job = ProcessingJob.queue(UUID.randomUUID(), UUID.randomUUID(), NOW);
    job.claim(NOW);

    job.recordProgress(3, 2, 1, 1, 1, NOW.plusSeconds(1));

    assertThat(job.getProcessedRows()).isEqualTo(3);
    assertThat(job.getValidRows()).isEqualTo(2);
    assertThat(job.getInvalidRows()).isOne();
    assertThat(job.getInsertedRows()).isOne();
    assertThat(job.getUpdatedRows()).isOne();
    assertThat(job.getHeartbeatAt()).isEqualTo(NOW.plusSeconds(1));
  }

  @Test
  void recordProgressRejectsADecreasingProcessedRowCount() {
    ProcessingJob job = ProcessingJob.queue(UUID.randomUUID(), UUID.randomUUID(), NOW);
    job.claim(NOW);
    job.recordProgress(3, 2, 1, 1, 1, NOW.plusSeconds(1));

    assertThatThrownBy(() -> job.recordProgress(2, 1, 1, 1, 0, NOW.plusSeconds(2)))
        .isInstanceOf(ProcessingJobRuleViolation.class)
        .extracting(exception -> ((ProcessingJobRuleViolation) exception).getRule())
        .isEqualTo(ProcessingJobRule.PROGRESS_CANNOT_DECREASE);
  }

  @Test
  void recordProgressRejectsCountersThatDoNotDescribeTheProcessedRows() {
    ProcessingJob job = ProcessingJob.queue(UUID.randomUUID(), UUID.randomUUID(), NOW);
    job.claim(NOW);

    assertThatThrownBy(() -> job.recordProgress(3, 1, 1, 1, 0, NOW.plusSeconds(1)))
        .isInstanceOf(ProcessingJobRuleViolation.class)
        .extracting(exception -> ((ProcessingJobRuleViolation) exception).getRule())
        .isEqualTo(ProcessingJobRule.INVALID_COUNTERS);
  }

  @Test
  void completePublishesAReportOnlyWhenInvalidRowsExist() {
    ProcessingJob job = ProcessingJob.queue(UUID.randomUUID(), UUID.randomUUID(), NOW);
    job.claim(NOW);
    job.recordProgress(3, 2, 1, 1, 1, NOW.plusSeconds(1));

    job.complete("reports/job.csv", NOW.plusSeconds(2));

    assertThat(job.getStatus()).isEqualTo(JobStatus.COMPLETED_WITH_ERRORS);
    assertThat(job.getErrorReportKey()).isEqualTo("reports/job.csv");
    assertThat(job.getFinishedAt()).isEqualTo(NOW.plusSeconds(2));
  }

  @Test
  void completeRejectsAReportWhenAllRowsAreValid() {
    ProcessingJob job = ProcessingJob.queue(UUID.randomUUID(), UUID.randomUUID(), NOW);
    job.claim(NOW);
    job.recordProgress(2, 2, 0, 1, 1, NOW.plusSeconds(1));

    assertThatThrownBy(() -> job.complete("reports/job.csv", NOW.plusSeconds(2)))
        .isInstanceOf(ProcessingJobRuleViolation.class)
        .extracting(exception -> ((ProcessingJobRuleViolation) exception).getRule())
        .isEqualTo(ProcessingJobRule.REPORT_AVAILABILITY_MISMATCH);
  }

  @Test
  void completeRejectsMissingReportWhenInvalidRowsExist() {
    ProcessingJob job = ProcessingJob.queue(UUID.randomUUID(), UUID.randomUUID(), NOW);
    job.claim(NOW);
    job.recordProgress(1, 0, 1, 0, 0, NOW.plusSeconds(1));

    assertThatThrownBy(() -> job.complete(null, NOW.plusSeconds(2)))
        .isInstanceOf(ProcessingJobRuleViolation.class)
        .extracting(exception -> ((ProcessingJobRuleViolation) exception).getRule())
        .isEqualTo(ProcessingJobRule.REPORT_AVAILABILITY_MISMATCH);
  }

  @Test
  void recordProgressRejectsUpsertsThatExceedValidRows() {
    ProcessingJob job = ProcessingJob.queue(UUID.randomUUID(), UUID.randomUUID(), NOW);
    job.claim(NOW);

    assertThatThrownBy(() -> job.recordProgress(1, 1, 0, 1, 1, NOW.plusSeconds(1)))
        .isInstanceOf(ProcessingJobRuleViolation.class)
        .extracting(exception -> ((ProcessingJobRuleViolation) exception).getRule())
        .isEqualTo(ProcessingJobRule.INVALID_COUNTERS);
  }

  @Test
  void retryKeepsFailedAttemptAndDefersTheNextAttemptUntilClaim() {
    ProcessingJob job = ProcessingJob.queue(UUID.randomUUID(), UUID.randomUUID(), NOW);
    job.claim(NOW);
    job.recordProgress(2, 2, 0, 1, 1, NOW.plusSeconds(1));
    job.fail("DATABASE_BATCH_FAILED", "database batch failed", NOW.plusSeconds(2));

    job.requestRetry(AttemptTrigger.USER_RETRY);

    assertThat(job.getStatus()).isEqualTo(JobStatus.QUEUED);
    assertThat(job.getProcessedRows()).isZero();
    assertThat(job.getCurrentAttempt()).isOne();
    assertThat(job.getAttempts())
        .singleElement()
        .extracting(ProcessingAttempt::getStatus)
        .isEqualTo(AttemptStatus.FAILED);
    assertThat(job.getAttempts())
        .singleElement()
        .extracting(
            ProcessingAttempt::getJobId,
            ProcessingAttempt::getProcessedRows,
            ProcessingAttempt::getValidRows,
            ProcessingAttempt::getInvalidRows,
            ProcessingAttempt::getInsertedRows,
            ProcessingAttempt::getUpdatedRows,
            ProcessingAttempt::getErrorCode)
        .containsExactly(job.getId(), 2L, 2L, 0L, 1L, 1L, "DATABASE_BATCH_FAILED");

    job.claim(NOW.plusSeconds(3));

    assertThat(job.getCurrentAttempt()).isEqualTo(2);
    assertThat(job.getAttempts())
        .element(1)
        .extracting(ProcessingAttempt::getTrigger)
        .isEqualTo(AttemptTrigger.USER_RETRY);
  }

  @Test
  void cancelAtSafePointClosesTheRunningAttempt() {
    ProcessingJob job = ProcessingJob.queue(UUID.randomUUID(), UUID.randomUUID(), NOW);
    job.claim(NOW);
    job.requestCancellation();

    job.cancel(NOW.plusSeconds(1));

    assertThat(job.getStatus()).isEqualTo(JobStatus.CANCELLED);
    assertThat(job.getAttempts())
        .singleElement()
        .extracting(ProcessingAttempt::getStatus)
        .isEqualTo(AttemptStatus.CANCELLED);
  }

  @Test
  void failRecordsTheSanitizedSystemErrorOnTheJob() {
    ProcessingJob job = ProcessingJob.queue(UUID.randomUUID(), UUID.randomUUID(), NOW);
    job.claim(NOW);

    job.fail("DATABASE_BATCH_FAILED", "database batch failed", NOW.plusSeconds(1));

    assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
    assertThat(job.getErrorCode()).isEqualTo("DATABASE_BATCH_FAILED");
    assertThat(job.getErrorSummary()).isEqualTo("database batch failed");
  }

  @Test
  void requestCancellationRejectsASuccessfullyCompletedJob() {
    ProcessingJob job = ProcessingJob.queue(UUID.randomUUID(), UUID.randomUUID(), NOW);
    job.claim(NOW);
    job.recordProgress(1, 1, 0, 1, 0, NOW.plusSeconds(1));
    job.complete(null, NOW.plusSeconds(2));

    assertThatThrownBy(job::requestCancellation)
        .isInstanceOf(ProcessingJobRuleViolation.class)
        .extracting(exception -> ((ProcessingJobRuleViolation) exception).getRule())
        .isEqualTo(ProcessingJobRule.JOB_NOT_CANCELLABLE);
  }

  @Test
  void retryRejectsASuccessfullyCompletedJob() {
    ProcessingJob job = ProcessingJob.queue(UUID.randomUUID(), UUID.randomUUID(), NOW);
    job.claim(NOW);
    job.recordProgress(1, 1, 0, 1, 0, NOW.plusSeconds(1));
    job.complete(null, NOW.plusSeconds(2));

    assertThatThrownBy(() -> job.requestRetry(AttemptTrigger.USER_RETRY))
        .isInstanceOf(ProcessingJobRuleViolation.class)
        .extracting(exception -> ((ProcessingJobRuleViolation) exception).getRule())
        .isEqualTo(ProcessingJobRule.JOB_NOT_RETRYABLE);
  }

  @Test
  void recordProgressRejectsNegativeCounters() {
    ProcessingJob job = ProcessingJob.queue(UUID.randomUUID(), UUID.randomUUID(), NOW);
    job.claim(NOW);

    assertThatThrownBy(() -> job.recordProgress(0, -1, 1, 0, -1, NOW.plusSeconds(1)))
        .isInstanceOf(ProcessingJobRuleViolation.class)
        .extracting(exception -> ((ProcessingJobRuleViolation) exception).getRule())
        .isEqualTo(ProcessingJobRule.INVALID_COUNTERS);
  }

  @Test
  void retryRejectsTheFourthUserTriggeredRetry() {
    ProcessingJob job = ProcessingJob.queue(UUID.randomUUID(), UUID.randomUUID(), NOW);
    for (int attempt = 0; attempt < 4; attempt++) {
      job.claim(NOW.plusSeconds(attempt * 2L));
      job.fail("DATABASE_BATCH_FAILED", "database batch failed", NOW.plusSeconds(attempt * 2L + 1));
      if (attempt < 3) {
        job.requestRetry(AttemptTrigger.USER_RETRY);
      }
    }

    assertThatThrownBy(() -> job.requestRetry(AttemptTrigger.USER_RETRY))
        .isInstanceOf(ProcessingJobRuleViolation.class)
        .extracting(exception -> ((ProcessingJobRuleViolation) exception).getRule())
        .isEqualTo(ProcessingJobRule.RETRY_LIMIT_EXCEEDED);
  }

  @Test
  void recordProgressCalculatesPercentageWhenTotalRowsAreKnown() {
    ProcessingJob job = ProcessingJob.queue(UUID.randomUUID(), UUID.randomUUID(), NOW);
    job.claim(NOW);

    job.recordProgress(10, 10, 0, 8, 2, 100L, NOW.plusSeconds(1));

    assertThat(job.getTotalRows()).isEqualTo(100L);
    assertThat(job.getProgressPercent()).isEqualTo(10);
  }

  @Test
  void completeRejectsAJobThatHasNotBeenClaimed() {
    ProcessingJob job = ProcessingJob.queue(UUID.randomUUID(), UUID.randomUUID(), NOW);

    assertThatThrownBy(() -> job.complete(null, NOW.plusSeconds(1)))
        .isInstanceOf(ProcessingJobRuleViolation.class)
        .extracting(exception -> ((ProcessingJobRuleViolation) exception).getRule())
        .isEqualTo(ProcessingJobRule.ONLY_PROCESSING_JOB_CAN_COMPLETE);
  }

  @Test
  void failRejectsAJobThatHasNotBeenClaimed() {
    ProcessingJob job = ProcessingJob.queue(UUID.randomUUID(), UUID.randomUUID(), NOW);

    assertThatThrownBy(() -> job.fail("DATABASE_BATCH_FAILED", "database batch failed", NOW))
        .isInstanceOf(ProcessingJobRuleViolation.class);
  }

  @Test
  void cancelRequiresACancellationRequestFromTheWorker() {
    ProcessingJob job = ProcessingJob.queue(UUID.randomUUID(), UUID.randomUUID(), NOW);
    job.claim(NOW);

    assertThatThrownBy(() -> job.cancel(NOW.plusSeconds(1)))
        .isInstanceOf(ProcessingJobRuleViolation.class);
  }

  @Test
  void recordProgressRejectsAJobThatHasNotBeenClaimed() {
    ProcessingJob job = ProcessingJob.queue(UUID.randomUUID(), UUID.randomUUID(), NOW);

    assertThatThrownBy(() -> job.recordProgress(1, 1, 0, 1, 0, NOW.plusSeconds(1)))
        .isInstanceOf(ProcessingJobRuleViolation.class);
  }

  @Test
  void recoveryClosesTheLostAttemptAndRequeuesTheJobWithoutSpendingARetry() {
    ProcessingJob job = ProcessingJob.queue(UUID.randomUUID(), UUID.randomUUID(), NOW);
    job.claim(NOW);
    job.recordProgress(5, 5, 0, 5, 0, NOW.plusSeconds(1));

    job.recoverFromStaleWorker("WORKER_HEARTBEAT_LOST", "worker stopped", NOW.plusSeconds(2));

    assertThat(job.getStatus()).isEqualTo(JobStatus.QUEUED);
    assertThat(job.getProcessedRows()).isZero();
    assertThat(job.getAttempts())
        .singleElement()
        .extracting(ProcessingAttempt::getStatus, ProcessingAttempt::getErrorCode)
        .containsExactly(AttemptStatus.FAILED, "WORKER_HEARTBEAT_LOST");

    job.claim(NOW.plusSeconds(3));

    assertThat(job.getAttempts())
        .element(1)
        .extracting(ProcessingAttempt::getTrigger)
        .isEqualTo(AttemptTrigger.RECOVERY);
  }

  @Test
  void recoveryDoesNotConsumeTheRetryBudgetAUserStillHas() {
    ProcessingJob job = ProcessingJob.queue(UUID.randomUUID(), UUID.randomUUID(), NOW);
    for (int recovery = 0; recovery < 3; recovery++) {
      job.claim(NOW.plusSeconds(recovery * 2L));
      job.recoverFromStaleWorker(
          "WORKER_HEARTBEAT_LOST", "worker stopped", NOW.plusSeconds(recovery * 2L + 1));
    }
    job.claim(NOW.plusSeconds(10));
    job.fail("DATABASE_BATCH_FAILED", "database batch failed", NOW.plusSeconds(11));

    job.requestRetry(AttemptTrigger.USER_RETRY);

    assertThat(job.getStatus()).isEqualTo(JobStatus.QUEUED);
  }

  @Test
  void recoveryStopsRequeueingAJobThatKeepsLosingItsWorker() {
    ProcessingJob job = ProcessingJob.queue(UUID.randomUUID(), UUID.randomUUID(), NOW);
    for (int recovery = 0; recovery < 4; recovery++) {
      job.claim(NOW.plusSeconds(recovery * 2L));
      job.recoverFromStaleWorker(
          "WORKER_HEARTBEAT_LOST", "worker stopped", NOW.plusSeconds(recovery * 2L + 1));
    }

    // The fourth loss leaves the job failed instead of queued: an endless requeue would occupy a
    // worker forever, and a person can still retry it deliberately.
    assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
    assertThat(job.getAttempts()).hasSize(4);
  }
}
