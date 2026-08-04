package com.vandunxg.file_processing.fileimport.application.validation;

import java.time.LocalDate;

public record NormalizedCustomerRow(
    String externalId,
    String fullName,
    String email,
    String phone,
    LocalDate dateOfBirth,
    String address) {}
