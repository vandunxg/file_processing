package com.vandunxg.file_processing.customer.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.vandunxg.file_processing.customer.application.capability.CustomerBatchWriter;
import com.vandunxg.file_processing.customer.application.command.ImportCustomerBatchCommand;
import com.vandunxg.file_processing.customer.application.command.ImportCustomerRow;
import com.vandunxg.file_processing.customer.application.result.ImportCustomerBatchResult;
import com.vandunxg.file_processing.customer.domain.model.Customer;
import org.junit.jupiter.api.Test;

class CustomerImportServiceTest {

  private static final UUID SOURCE_JOB_ID = UUID.randomUUID();
  private static final LocalDate DATE_OF_BIRTH = LocalDate.parse("2000-01-02");

  private final RecordingCustomerBatchWriter writer = new RecordingCustomerBatchWriter();
  private final CustomerImportService service = new CustomerImportService(writer);

  @Test
  void importBatchStampsEverySnapshotWithTheSourceJobAndReturnsTheWriterCounts() {
    writer.result = new ImportCustomerBatchResult(1, 1);

    ImportCustomerBatchResult result =
        service.importBatch(
            new ImportCustomerBatchCommand(
                SOURCE_JOB_ID, List.of(row("CUS_01", "1 Main St"), row("CUS_02", "  "))));

    assertThat(result).isEqualTo(new ImportCustomerBatchResult(1, 1));
    assertThat(writer.batches).hasSize(1);
    assertThat(writer.batches.getFirst())
        .extracting(Customer::getExternalId, Customer::getAddress, Customer::getLastImportJobId)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("CUS_01", "1 Main St", SOURCE_JOB_ID),
            org.assertj.core.groups.Tuple.tuple("CUS_02", null, SOURCE_JOB_ID));
  }

  @Test
  void importBatchSkipsTheWriterWhenTheBatchIsEmpty() {
    ImportCustomerBatchResult result =
        service.importBatch(new ImportCustomerBatchCommand(SOURCE_JOB_ID, List.of()));

    assertThat(result).isEqualTo(new ImportCustomerBatchResult(0, 0));
    assertThat(writer.batches).isEmpty();
  }

  private static ImportCustomerRow row(String externalId, String address) {
    return new ImportCustomerRow(
        externalId,
        "Nguyen Van A",
        externalId.toLowerCase() + "@example.com",
        "+84912345678",
        DATE_OF_BIRTH,
        address);
  }

  private static final class RecordingCustomerBatchWriter implements CustomerBatchWriter {

    private final List<List<Customer>> batches = new ArrayList<>();
    private ImportCustomerBatchResult result = new ImportCustomerBatchResult(0, 0);

    @Override
    public ImportCustomerBatchResult upsertAll(List<Customer> customers) {
      batches.add(List.copyOf(customers));
      return result;
    }
  }
}
