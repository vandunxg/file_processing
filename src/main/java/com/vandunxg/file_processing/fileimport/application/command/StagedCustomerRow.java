package com.vandunxg.file_processing.fileimport.application.command;

import java.util.List;
import java.util.Optional;

import com.vandunxg.file_processing.fileimport.application.validation.NormalizedCustomerRow;
import com.vandunxg.file_processing.fileimport.application.validation.ParsedCustomerRow;
import com.vandunxg.file_processing.fileimport.application.validation.ValidationIssue;

/** One parsed source row, its validation result, and any issues to include in the final report. */
public record StagedCustomerRow(
    ParsedCustomerRow originalRow,
    Optional<NormalizedCustomerRow> normalizedRow,
    List<ValidationIssue> issues) {

  public StagedCustomerRow {
    normalizedRow = normalizedRow == null ? Optional.empty() : normalizedRow;
    issues = List.copyOf(issues);
  }
}
