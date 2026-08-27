package com.vandunxg.file_processing.fileimport.application.validation;

public record ValidationIssue(
    long rowNumber, String externalId, ValidationErrorCode code, String field, String message) {}
