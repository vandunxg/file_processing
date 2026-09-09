package com.vandunxg.file_processing.fileimport.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.vandunxg.file_processing.fileimport.domain.exception.ProcessingJobRule;
import com.vandunxg.file_processing.fileimport.domain.exception.ProcessingJobRuleViolation;
import org.junit.jupiter.api.Test;

class RowCountersTest {

  @Test
  void totalsThatDescribeARealRunAreAccepted() {
    assertThatCode(() -> new RowCounters(3, 2, 1, 1, 1).requireConsistent())
        .doesNotThrowAnyException();
  }

  @Test
  void processedMustBeTheValidAndInvalidRowsItIsMadeOf() {
    assertThatThrownBy(() -> new RowCounters(3, 1, 1, 1, 0).requireConsistent())
        .isInstanceOf(ProcessingJobRuleViolation.class)
        .extracting(violation -> ((ProcessingJobRuleViolation) violation).getRule())
        .isEqualTo(ProcessingJobRule.INVALID_COUNTERS);
  }

  @Test
  void noMoreRowsMayBeWrittenThanWereFoundValid() {
    assertThatThrownBy(() -> new RowCounters(1, 1, 0, 1, 1).requireConsistent())
        .isInstanceOf(ProcessingJobRuleViolation.class)
        .extracting(violation -> ((ProcessingJobRuleViolation) violation).getRule())
        .isEqualTo(ProcessingJobRule.INVALID_COUNTERS);
  }

  @Test
  void noTotalMayBeNegative() {
    assertThatThrownBy(() -> new RowCounters(0, -1, 1, 0, -1).requireConsistent())
        .isInstanceOf(ProcessingJobRuleViolation.class)
        .extracting(violation -> ((ProcessingJobRuleViolation) violation).getRule())
        .isEqualTo(ProcessingJobRule.INVALID_COUNTERS);
  }

  /**
   * Reconstitution replays a stored row, which is a fact rather than a new request (RULE.md §6.4).
   * Judging it here would make a legacy row that never satisfied the rule unreadable, so the check
   * is a method the caller invokes and not a constructor guard.
   */
  @Test
  void constructionAcceptsInconsistentStoredTotalsSoAFactStaysReadable() {
    assertThatCode(() -> new RowCounters(99, 1, 1, 0, 0)).doesNotThrowAnyException();
  }

  /** Named construction is the point: two totals of the same type cannot be swapped unnoticed. */
  @Test
  void theBuilderNamesEveryTotal() {
    RowCounters counters =
        RowCounters.builder()
            .processedRows(5)
            .validRows(3)
            .invalidRows(2)
            .insertedRows(1)
            .updatedRows(2)
            .build();

    assertThat(counters.processedRows()).isEqualTo(5);
    assertThat(counters.validRows()).isEqualTo(3);
    assertThat(counters.invalidRows()).isEqualTo(2);
    assertThat(counters.insertedRows()).isEqualTo(1);
    assertThat(counters.updatedRows()).isEqualTo(2);
  }
}
