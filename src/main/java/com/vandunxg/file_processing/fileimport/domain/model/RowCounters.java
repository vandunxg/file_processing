package com.vandunxg.file_processing.fileimport.domain.model;

import com.vandunxg.file_processing.fileimport.domain.exception.ProcessingJobRule;
import com.vandunxg.file_processing.fileimport.domain.exception.ProcessingJobRuleViolation;
import lombok.Builder;

/**
 * The five totals a run reports, which always travel together.
 *
 * <p>They were five loose {@code long} parameters on every method that carried them -- recording
 * progress, finishing an attempt, rebuilding either from a row. Five consecutive parameters of one
 * type is a place where a swapped pair compiles silently, and two of the possible swaps survive
 * every consistency check below: {@code valid} against {@code invalid}, because the sum that
 * defines {@code processed} is symmetric, and {@code inserted} against {@code updated}. Those would
 * misreport an import to its owner with nothing failing. Hence the builder, so production call
 * sites name what they are setting.
 */
@Builder
public record RowCounters(
    long processedRows, long validRows, long invalidRows, long insertedRows, long updatedRows) {

  /**
   * Rejects totals that cannot describe a real run: a processed count that is not the valid and
   * invalid rows it is made of, more rows written than were found valid, or a negative total.
   *
   * <p>Deliberately a method rather than a constructor guard, for two reasons. Reconstitution
   * replays a stored row, which is a fact and not a new request ({@code RULE.md} §6.4) -- judging
   * it here would make a legacy row that never satisfied the rule unreadable. And the aggregate
   * checks its own state first, so a caller writing to a job that is no longer running learns that,
   * rather than being told about arithmetic it can do nothing about.
   */
  public void requireConsistent() {
    if (processedRows < 0
        || validRows < 0
        || invalidRows < 0
        || insertedRows < 0
        || updatedRows < 0
        || processedRows != validRows + invalidRows
        || insertedRows + updatedRows > validRows) {
      throw new ProcessingJobRuleViolation(ProcessingJobRule.INVALID_COUNTERS);
    }
  }
}
