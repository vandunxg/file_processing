package com.vandunxg.file_processing.fileimport.application.service;

public record CustomerImportResult(
    long processedRows, long validRows, long invalidRows, long insertedRows, long updatedRows) {}
