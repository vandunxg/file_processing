package com.vandunxg.file_processing.fileimport.application.validation;

import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class CustomerRowValidator {

  private static final DateTimeFormatter DATE_FORMAT =
      DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

  private final Clock clock;

  public CustomerRowValidator(Clock clock) {
    this.clock = clock;
  }

  public ValidatedCustomerRow validate(ParsedCustomerRow row) {
    List<ValidationIssue> issues = new ArrayList<>();
    String externalId = normalize(row.externalId());
    String fullName = normalize(row.fullName()).replaceAll("\\s+", " ");
    String email = normalize(row.email()).toLowerCase(Locale.ROOT);
    String phone = normalize(row.phone()).replaceAll("[\\s.-]", "");
    String address = normalize(row.address());

    validateExternalId(row, externalId, issues);
    validateFullName(row, externalId, fullName, issues);
    validateEmail(row, externalId, email, issues);
    String normalizedPhone = validatePhone(row, externalId, phone, issues);
    LocalDate dateOfBirth = validateDateOfBirth(row, externalId, issues);
    validateAddress(row, externalId, address, issues);

    if (!issues.isEmpty()) {
      return new ValidatedCustomerRow(Optional.empty(), issues);
    }
    return new ValidatedCustomerRow(
        Optional.of(
            new NormalizedCustomerRow(
                externalId, fullName, email, normalizedPhone, dateOfBirth, emptyToNull(address))),
        issues);
  }

  private static void validateExternalId(
      ParsedCustomerRow row, String externalId, List<ValidationIssue> issues) {
    if (externalId.isEmpty()) {
      issue(
          row,
          null,
          ValidationErrorCode.REQUIRED_FIELD,
          "external_id",
          "External ID is required",
          issues);
    } else if (externalId.length() > 64 || !externalId.matches("[A-Za-z0-9_-]+")) {
      issue(
          row,
          externalId,
          ValidationErrorCode.INVALID_EXTERNAL_ID,
          "external_id",
          "External ID is invalid",
          issues);
    }
  }

  private static void validateFullName(
      ParsedCustomerRow row, String externalId, String fullName, List<ValidationIssue> issues) {
    if (fullName.isEmpty()) {
      issue(
          row,
          externalId,
          ValidationErrorCode.REQUIRED_FIELD,
          "full_name",
          "Full name is required",
          issues);
    } else if (fullName.length() < 2) {
      issue(
          row,
          externalId,
          ValidationErrorCode.FULL_NAME_TOO_SHORT,
          "full_name",
          "Full name is too short",
          issues);
    } else if (fullName.length() > 150) {
      issue(
          row,
          externalId,
          ValidationErrorCode.FULL_NAME_TOO_LONG,
          "full_name",
          "Full name is too long",
          issues);
    }
  }

  private static void validateEmail(
      ParsedCustomerRow row, String externalId, String email, List<ValidationIssue> issues) {
    if (email.isEmpty()) {
      issue(
          row,
          externalId,
          ValidationErrorCode.REQUIRED_FIELD,
          "email",
          "Email is required",
          issues);
    } else if (email.length() > 254 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
      issue(
          row, externalId, ValidationErrorCode.INVALID_EMAIL, "email", "Email is invalid", issues);
    }
  }

  private static String validatePhone(
      ParsedCustomerRow row, String externalId, String phone, List<ValidationIssue> issues) {
    if (phone.isEmpty()) {
      issue(
          row,
          externalId,
          ValidationErrorCode.REQUIRED_FIELD,
          "phone",
          "Phone is required",
          issues);
      return null;
    }
    if (phone.matches("0\\d{9}")) {
      return "+84" + phone.substring(1);
    }
    if (phone.matches("\\+84\\d{9}")) {
      return phone;
    }
    issue(row, externalId, ValidationErrorCode.INVALID_PHONE, "phone", "Phone is invalid", issues);
    return null;
  }

  private LocalDate validateDateOfBirth(
      ParsedCustomerRow row, String externalId, List<ValidationIssue> issues) {
    String value = normalize(row.dateOfBirth());
    if (value.isEmpty()) {
      issue(
          row,
          externalId,
          ValidationErrorCode.REQUIRED_FIELD,
          "date_of_birth",
          "Date of birth is required",
          issues);
      return null;
    }
    try {
      LocalDate dateOfBirth = LocalDate.parse(value, DATE_FORMAT);
      LocalDate today = LocalDate.now(clock);
      if (dateOfBirth.isAfter(today)) {
        issue(
            row,
            externalId,
            ValidationErrorCode.DATE_OF_BIRTH_IN_FUTURE,
            "date_of_birth",
            "Date of birth cannot be in the future",
            issues);
      } else if (dateOfBirth.isBefore(today.minusYears(120))) {
        issue(
            row,
            externalId,
            ValidationErrorCode.DATE_OF_BIRTH_TOO_OLD,
            "date_of_birth",
            "Date of birth is too old",
            issues);
      }
      return dateOfBirth;
    } catch (DateTimeParseException exception) {
      issue(
          row,
          externalId,
          ValidationErrorCode.INVALID_DATE_FORMAT,
          "date_of_birth",
          "Date of birth must use yyyy-MM-dd",
          issues);
      return null;
    }
  }

  private static void validateAddress(
      ParsedCustomerRow row, String externalId, String address, List<ValidationIssue> issues) {
    if (address.length() > 500) {
      issue(
          row,
          externalId,
          ValidationErrorCode.ADDRESS_TOO_LONG,
          "address",
          "Address is too long",
          issues);
    }
  }

  private static void issue(
      ParsedCustomerRow row,
      String externalId,
      ValidationErrorCode code,
      String field,
      String message,
      List<ValidationIssue> issues) {
    issues.add(new ValidationIssue(row.rowNumber(), emptyToNull(externalId), code, field, message));
  }

  private static String normalize(String value) {
    return value == null ? "" : value.trim();
  }

  private static String emptyToNull(String value) {
    return value == null || value.isEmpty() ? null : value;
  }
}
