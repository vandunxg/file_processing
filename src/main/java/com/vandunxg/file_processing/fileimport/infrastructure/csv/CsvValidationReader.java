package com.vandunxg.file_processing.fileimport.infrastructure.csv;

import java.io.InputStream;
import java.time.Clock;
import java.util.Optional;

import com.vandunxg.file_processing.fileimport.application.capability.CustomerCsvReader;
import com.vandunxg.file_processing.fileimport.application.validation.CustomerRowValidator;
import com.vandunxg.file_processing.fileimport.application.validation.ParsedCustomerRow;
import com.vandunxg.file_processing.fileimport.application.validation.ValidatedCustomerRow;

public final class CsvValidationReader implements CustomerCsvReader.Run {

  private final CsvRecordReader reader;
  private final CustomerRowValidator validator;

  public CsvValidationReader(InputStream input, Clock clock) {
    reader = new CsvRecordReader(input);
    validator = new CustomerRowValidator(clock);
  }

  @Override
  public Optional<ValidatedCustomerRow> next() {
    return reader.next().map(this::validate);
  }

  @Override
  public void close() {
    reader.close();
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
    return new ValidatedCustomerRow(validated.row(), validated.issues(), originalRow);
  }
}
