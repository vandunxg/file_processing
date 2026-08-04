package com.vandunxg.file_processing.fileimport.adapter.in.csv;

public record ParsedCsvRow(
    long rowNumber,
    String externalId,
    String fullName,
    String email,
    String phone,
    String dateOfBirth,
    String address) {}
