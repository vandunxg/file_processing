package com.vandunxg.file_processing.fileimport.application.service;

import java.io.InputStream;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

import com.vandunxg.file_processing.fileimport.adapter.in.csv.CsvValidationReader;
import com.vandunxg.file_processing.fileimport.adapter.out.persistence.CustomerUpsertResult;
import com.vandunxg.file_processing.fileimport.application.port.out.DuplicateExternalIdTracker;
import com.vandunxg.file_processing.fileimport.application.validation.NormalizedCustomerRow;
import com.vandunxg.file_processing.fileimport.application.validation.ValidatedCustomerRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CustomerImportProcessor {

  private static final int BATCH_SIZE = 1_000;

  private final Clock clock;
  private final DuplicateExternalIdTracker duplicateExternalIdTracker;

  public CustomerImportResult process(
      InputStream input,
      Function<List<NormalizedCustomerRow>, CustomerUpsertResult> upsertBatch,
      Consumer<ValidatedCustomerRow> invalidRowConsumer) {
    List<NormalizedCustomerRow> batch = new ArrayList<>(BATCH_SIZE);
    Counters counters = new Counters();
    try (var reader = new CsvValidationReader(input, clock, duplicateExternalIdTracker)) {
      while (true) {
        var next = reader.next();
        if (next.isEmpty()) {
          break;
        }
        ValidatedCustomerRow validated = next.orElseThrow();
        counters.processedRows++;
        if (validated.row().isEmpty()) {
          counters.invalidRows++;
          invalidRowConsumer.accept(validated);
          continue;
        }
        counters.validRows++;
        batch.add(validated.row().orElseThrow());
        if (batch.size() == BATCH_SIZE) {
          counters.add(upsertBatch.apply(List.copyOf(batch)));
          batch.clear();
        }
      }
    }
    if (!batch.isEmpty()) {
      counters.add(upsertBatch.apply(List.copyOf(batch)));
    }
    return counters.toResult();
  }

  private static final class Counters {

    private long processedRows;
    private long validRows;
    private long invalidRows;
    private long insertedRows;
    private long updatedRows;

    private void add(CustomerUpsertResult result) {
      insertedRows += result.insertedRows();
      updatedRows += result.updatedRows();
    }

    private CustomerImportResult toResult() {
      return new CustomerImportResult(
          processedRows, validRows, invalidRows, insertedRows, updatedRows);
    }
  }
}
