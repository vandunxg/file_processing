package com.vandunxg.file_processing.auth.domain.exception;

import java.util.Objects;

/**
 * Raised when an auth aggregate refuses a state change. Carries only the violated {@link AuthRule};
 * translating it into the module error catalog is the application layer's job.
 */
public class AuthRuleViolation extends RuntimeException {

  private final AuthRule rule;

  public AuthRuleViolation(AuthRule rule) {
    super(Objects.requireNonNull(rule, "rule").name());
    this.rule = rule;
  }

  public AuthRule getRule() {
    return rule;
  }
}
