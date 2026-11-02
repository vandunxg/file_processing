package com.vandunxg.file_processing.fileimport.infrastructure.csv;

public record ParsedCsvRow(
    long rowNumber,
    String externalId,
    String fullName,
    String email,
    String phone,
    String dateOfBirth,
    String address) {}
