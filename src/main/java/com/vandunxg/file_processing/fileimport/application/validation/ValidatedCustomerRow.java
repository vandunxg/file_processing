package com.vandunxg.file_processing.fileimport.application.validation;

import java.util.List;
import java.util.Optional;

public record ValidatedCustomerRow(
    Optional<NormalizedCustomerRow> row, List<ValidationIssue> issues) {

  public ValidatedCustomerRow {
    issues = List.copyOf(issues);
  }
}
