package com.vandunxg.file_processing.fileimport.application.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class CustomerRowValidatorTest {

  private final CustomerRowValidator validator =
      new CustomerRowValidator(Clock.fixed(Instant.parse("2026-08-04T00:00:00Z"), ZoneOffset.UTC));

  @Test
  void normalizesAValidCustomerRow() {
    var result =
        validator.validate(
            new ParsedCustomerRow(
                2,
                " CUS_01 ",
                "  Nguyen   Van  A  ",
                " USER@EXAMPLE.COM ",
                " 0912-345 678 ",
                " 2000-01-02 ",
                "  1 Main Street  "));

    assertThat(result.issues()).isEmpty();
    assertThat(result.row())
        .hasValue(
            new NormalizedCustomerRow(
                "CUS_01",
                "Nguyen Van A",
                "user@example.com",
                "+84912345678",
                java.time.LocalDate.parse("2000-01-02"),
                "1 Main Street"));
  }

  @Test
  void collectsEveryIssueForAnInvalidRow() {
    var result =
        validator.validate(
            new ParsedCustomerRow(7, " ", " A ", "invalid", "123", "2026-08-05", "x".repeat(501)));

    assertThat(result.row()).isEmpty();
    assertThat(result.issues())
        .extracting(ValidationIssue::code)
        .containsExactly(
            ValidationErrorCode.REQUIRED_FIELD,
            ValidationErrorCode.FULL_NAME_TOO_SHORT,
            ValidationErrorCode.INVALID_EMAIL,
            ValidationErrorCode.INVALID_PHONE,
            ValidationErrorCode.DATE_OF_BIRTH_IN_FUTURE,
            ValidationErrorCode.ADDRESS_TOO_LONG);
  }
}
