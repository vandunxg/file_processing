package com.vandunxg.file_processing.customer.application.result;

/** How a customer batch divided between newly created and already existing customers. */
public record ImportCustomerBatchResult(long insertedRows, long updatedRows) {

  public static final ImportCustomerBatchResult EMPTY = new ImportCustomerBatchResult(0, 0);
}
