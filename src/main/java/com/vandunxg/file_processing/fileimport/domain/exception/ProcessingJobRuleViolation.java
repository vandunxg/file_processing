package com.vandunxg.file_processing.fileimport.domain.exception;

import java.util.Objects;

/** Raised when a processing-job state transition violates a domain rule. */
public class ProcessingJobRuleViolation extends RuntimeException {

  private final ProcessingJobRule rule;

  public ProcessingJobRuleViolation(ProcessingJobRule rule) {
    super(Objects.requireNonNull(rule, "rule").name());
    this.rule = rule;
  }

  public ProcessingJobRule getRule() {
    return rule;
  }
}
