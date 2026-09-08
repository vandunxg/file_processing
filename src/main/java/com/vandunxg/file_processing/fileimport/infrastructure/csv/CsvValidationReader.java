package com.vandunxg.file_processing.fileimport.infrastructure.csv;

import java.io.InputStream;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.vandunxg.file_processing.fileimport.application.capability.CustomerCsvReader;
import com.vandunxg.file_processing.fileimport.application.capability.DuplicateExternalIdTracker;
import com.vandunxg.file_processing.fileimport.application.validation.CustomerRowValidator;
import com.vandunxg.file_processing.fileimport.application.validation.ParsedCustomerRow;
import com.vandunxg.file_processing.fileimport.application.validation.ValidatedCustomerRow;
import com.vandunxg.file_processing.fileimport.application.validation.ValidationErrorCode;
import com.vandunxg.file_processing.fileimport.application.validation.ValidationIssue;

public final class CsvValidationReader implements CustomerCsvReader.Run {

  private final CsvRecordReader reader;
  private final CustomerRowValidator validator;
  private final DuplicateExternalIdTracker.Run duplicateTracker;

  public CsvValidationReader(InputStream input, Clock clock, DuplicateExternalIdTracker tracker) {
    reader = new CsvRecordReader(input);
    validator = new CustomerRowValidator(clock);
    try {
      duplicateTracker = tracker.open();
    } catch (RuntimeException exception) {
      reader.close();
      throw exception;
    }
  }

  @Override
  public Optional<ValidatedCustomerRow> next() {
    return reader.next().map(this::validate);
  }

  @Override
  public void close() {
    try {
      duplicateTracker.close();
    } finally {
      reader.close();
    }
  }

  private ValidatedCustomerRow validate(ParsedCsvRow parsed) {
    ParsedCustomerRow originalRow =
        new ParsedCustomerRow(
            parsed.rowNumber(),
            parsed.externalId(),
            parsed.fullName(),
            parsed.email(),
            parsed.phone(),
            parsed.dateOfBirth(),
            parsed.address());
    ValidatedCustomerRow validated = validator.validate(originalRow);
    String externalId = CustomerRowValidator.normalizeExternalId(originalRow.externalId());
    if (validated.row().isPresent() && duplicateTracker.firstOccurrence(externalId)) {
      return new ValidatedCustomerRow(validated.row(), validated.issues(), originalRow);
    }
    if (validated.row().isEmpty()
        && (!CustomerRowValidator.isValidExternalId(externalId)
            || !duplicateTracker.alreadySeen(externalId))) {
      return new ValidatedCustomerRow(validated.row(), validated.issues(), originalRow);
    }
    List<ValidationIssue> issues = new ArrayList<>(validated.issues());
    issues.add(
        new ValidationIssue(
            parsed.rowNumber(),
            externalId,
            ValidationErrorCode.DUPLICATE_EXTERNAL_ID_IN_FILE,
            "external_id",
            "External ID appears more than once in the file"));
    return new ValidatedCustomerRow(Optional.empty(), List.copyOf(issues), originalRow);
  }
}
