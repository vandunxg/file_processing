package com.vandunxg.file_processing.customer.application.service;

import java.util.List;

import com.vandunxg.file_processing.customer.application.capability.CustomerBatchWriter;
import com.vandunxg.file_processing.customer.application.command.ImportCustomerBatchCommand;
import com.vandunxg.file_processing.customer.application.command.ImportCustomerRow;
import com.vandunxg.file_processing.customer.application.result.ImportCustomerBatchResult;
import com.vandunxg.file_processing.customer.domain.model.Customer;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Entry point other modules use to persist imported customer data. */
@Service
@RequiredArgsConstructor
public class CustomerImportService {

  private final CustomerBatchWriter customerBatchWriter;

  /** Persists one logical batch. The batch is the transaction: all rows land, or none do. */
  @Transactional
  public ImportCustomerBatchResult importBatch(ImportCustomerBatchCommand command) {
    if (command.rows().isEmpty()) {
      return ImportCustomerBatchResult.EMPTY;
    }
    List<Customer> customers =
        command.rows().stream().map(row -> snapshotOf(row, command)).toList();
    return customerBatchWriter.upsertAll(customers);
  }

  private static Customer snapshotOf(ImportCustomerRow row, ImportCustomerBatchCommand command) {
    return Customer.importedFrom(
        row.externalId(),
        row.fullName(),
        row.email(),
        row.phone(),
        row.dateOfBirth(),
        row.address(),
        command.sourceJobId());
  }
}
