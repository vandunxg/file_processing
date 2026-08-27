package com.vandunxg.file_processing.fileimport.application.validation;

import java.util.List;
import java.util.Optional;

public record ValidatedCustomerRow(
    Optional<NormalizedCustomerRow> row,
    List<ValidationIssue> issues,
    ParsedCustomerRow originalRow) {

  public ValidatedCustomerRow {
    issues = List.copyOf(issues);
  }

  public ValidatedCustomerRow(Optional<NormalizedCustomerRow> row, List<ValidationIssue> issues) {
    this(row, issues, null);
  }
}
