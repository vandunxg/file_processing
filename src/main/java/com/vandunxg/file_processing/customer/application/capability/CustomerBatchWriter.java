package com.vandunxg.file_processing.customer.application.capability;

import java.util.List;

import com.vandunxg.file_processing.customer.application.result.ImportCustomerBatchResult;
import com.vandunxg.file_processing.customer.domain.model.Customer;

/**
 * Persists a whole batch of customer snapshots atomically.
 *
 * <p>The batch is the transaction: either every snapshot in it is applied or none is.
 * Implementations insert customers whose {@code externalId} is new and overwrite the imported
 * fields of the rest, without changing an existing customer's internal identity.
 */
public interface CustomerBatchWriter {

  ImportCustomerBatchResult upsertAll(List<Customer> customers);
}
