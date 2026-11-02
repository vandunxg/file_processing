package com.vandunxg.file_processing.customer.application.command;

import java.time.LocalDate;

/**
 * One normalized, already-validated customer row a caller asks to persist.
 *
 * <p>The importing module owns row parsing and business validation, so this contract carries no raw
 * file data and no per-file diagnostics.
 */
public record ImportCustomerRow(
    String externalId,
    String fullName,
    String email,
    String phone,
    LocalDate dateOfBirth,
    String address) {}
