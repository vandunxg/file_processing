package com.vandunxg.file_processing.fileimport.application.validation;

public record ParsedCustomerRow(
    long rowNumber,
    String externalId,
    String fullName,
    String email,
    String phone,
    String dateOfBirth,
    String address) {}
