package com.vandunxg.file_processing.customer.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class CustomerTest {

  private static final UUID SOURCE_JOB_ID = UUID.randomUUID();
  private static final LocalDate DATE_OF_BIRTH = LocalDate.parse("2000-01-02");

  @Test
  void importedFromCapturesTheNormalizedSnapshotAndTheJobResponsibleForIt() {
    Customer customer =
        Customer.importedFrom(
            "CUS_01",
            "Nguyen Van A",
            "a@example.com",
            "+84912345678",
            DATE_OF_BIRTH,
            "1 Main St",
            SOURCE_JOB_ID);

    assertThat(customer.getId()).isNotNull();
    assertThat(customer.getExternalId()).isEqualTo("CUS_01");
    assertThat(customer.getFullName()).isEqualTo("Nguyen Van A");
    assertThat(customer.getEmail()).isEqualTo("a@example.com");
    assertThat(customer.getPhone()).isEqualTo("+84912345678");
    assertThat(customer.getDateOfBirth()).isEqualTo(DATE_OF_BIRTH);
    assertThat(customer.getAddress()).isEqualTo("1 Main St");
    assertThat(customer.getLastImportJobId()).isEqualTo(SOURCE_JOB_ID);
  }

  @Test
  void importedFromStoresABlankAddressAsNullSoItOverwritesTheStoredAddress() {
    Customer customer =
        Customer.importedFrom(
            "CUS_01",
            "Nguyen Van A",
            "a@example.com",
            "+84912345678",
            DATE_OF_BIRTH,
            "   ",
            SOURCE_JOB_ID);

    assertThat(customer.getAddress()).isNull();
  }

  @Test
  void importedFromRejectsAMissingRequiredSnapshotField() {
    assertThatThrownBy(
            () ->
                Customer.importedFrom(
                    " ",
                    "Nguyen Van A",
                    "a@example.com",
                    "+84912345678",
                    DATE_OF_BIRTH,
                    null,
                    SOURCE_JOB_ID))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(
            () ->
                Customer.importedFrom(
                    "CUS_01",
                    " ",
                    "a@example.com",
                    "+84912345678",
                    DATE_OF_BIRTH,
                    null,
                    SOURCE_JOB_ID))
        .isInstanceOf(IllegalArgumentException.class);

    assertThatThrownBy(
            () ->
                Customer.importedFrom(
                    "CUS_01",
                    "Nguyen Van A",
                    "a@example.com",
                    "+84912345678",
                    null,
                    null,
                    SOURCE_JOB_ID))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void importedFromRejectsASnapshotWithoutTheJobResponsibleForIt() {
    assertThatThrownBy(
            () ->
                Customer.importedFrom(
                    "CUS_01",
                    "Nguyen Van A",
                    "a@example.com",
                    "+84912345678",
                    DATE_OF_BIRTH,
                    "1 Main St",
                    null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
