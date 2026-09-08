package com.vandunxg.file_processing.customer.application.command;

import java.util.List;
import java.util.UUID;

/**
 * One logical batch of customer rows to persist under a single transaction.
 *
 * <p>{@code sourceJobId} is the external identity of the processing job responsible for the state
 * this batch writes. Rows must already be de-duplicated by {@code externalId}: a batch that names
 * the same customer twice cannot be applied atomically.
 */
public record ImportCustomerBatchCommand(UUID sourceJobId, List<ImportCustomerRow> rows) {}
