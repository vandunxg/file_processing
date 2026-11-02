package com.vandunxg.file_processing.fileimport.infrastructure.csv;

import java.io.InputStream;
import java.time.Clock;

import com.vandunxg.file_processing.fileimport.application.capability.CustomerCsvReader;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Apache Commons CSV implementation of the customer row source. */
@Component
@RequiredArgsConstructor
public class CommonsCustomerCsvReader implements CustomerCsvReader {

  private final Clock clock;

  @Override
  public void validateHeader(InputStream input) {
    try (var reader = new CsvRecordReader(input)) {
      reader.next();
    }
  }

  @Override
  public Run open(InputStream input) {
    return new CsvValidationReader(input, clock);
  }
}
