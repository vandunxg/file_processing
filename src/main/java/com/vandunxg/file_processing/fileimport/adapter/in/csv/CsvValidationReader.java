package com.vandunxg.file_processing.fileimport.adapter.in.csv;

import java.io.InputStream;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

import com.vandunxg.file_processing.fileimport.application.port.out.DuplicateExternalIdTracker;
import com.vandunxg.file_processing.fileimport.application.validation.CustomerRowValidator;
import com.vandunxg.file_processing.fileimport.application.validation.ParsedCustomerRow;
import com.vandunxg.file_processing.fileimport.application.validation.ValidatedCustomerRow;
import com.vandunxg.file_processing.fileimport.application.validation.ValidationErrorCode;
import com.vandunxg.file_processing.fileimport.application.validation.ValidationIssue;

public final class CsvValidationReader implements AutoCloseable {

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
    if (validated.row().isEmpty()
        || duplicateTracker.firstOccurrence(validated.row().orElseThrow().externalId())) {
      return new ValidatedCustomerRow(validated.row(), validated.issues(), originalRow);
    }
    return new ValidatedCustomerRow(
        Optional.empty(),
        List.of(
            new ValidationIssue(
                parsed.rowNumber(),
                validated.row().orElseThrow().externalId(),
                ValidationErrorCode.DUPLICATE_EXTERNAL_ID_IN_FILE,
                "external_id",
                "External ID appears more than once in the file")),
        originalRow);
  }
}
