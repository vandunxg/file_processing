package com.vandunxg.file_processing.customer.domain.model;

import java.time.LocalDate;
import java.util.UUID;

import com.vandunxg.common.utils.IdUtils;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/**
 * Customer business identity and the normalized snapshot persisted for it.
 *
 * <p>A snapshot always names the import job responsible for the state it carries, so provenance
 * survives every later import.
 */
@Getter
@ToString
@EqualsAndHashCode(of = "id")
public class Customer {

  private final UUID id;
  private final String externalId;
  private final String fullName;
  private final String email;
  private final String phone;
  private final LocalDate dateOfBirth;
  private final String address;
  private final UUID lastImportJobId;

  private Customer(
      UUID id,
      String externalId,
      String fullName,
      String email,
      String phone,
      LocalDate dateOfBirth,
      String address,
      UUID lastImportJobId) {
    this.id = id;
    this.externalId = externalId;
    this.fullName = fullName;
    this.email = email;
    this.phone = phone;
    this.dateOfBirth = dateOfBirth;
    this.address = address;
    this.lastImportJobId = lastImportJobId;
  }

  /**
   * Builds the customer state a single normalized import row asks to persist.
   *
   * <p>The identity generated here is only used when the {@code externalId} is new. An import never
   * changes the internal identity of a customer that already exists.
   */
  public static Customer importedFrom(
      String externalId,
      String fullName,
      String email,
      String phone,
      LocalDate dateOfBirth,
      String address,
      UUID lastImportJobId) {
    if (isBlank(externalId)
        || isBlank(fullName)
        || isBlank(email)
        || isBlank(phone)
        || dateOfBirth == null
        || lastImportJobId == null) {
      throw new IllegalArgumentException("Imported customer snapshot is incomplete");
    }
    return new Customer(
        IdUtils.nextId(),
        externalId,
        fullName,
        email,
        phone,
        dateOfBirth,
        emptyToNull(address),
        lastImportJobId);
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static String emptyToNull(String address) {
    return isBlank(address) ? null : address;
  }
}
