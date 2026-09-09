package com.vandunxg.file_processing.fileimport.application.result;

import com.vandunxg.file_processing.fileimport.application.validation.ParsedCustomerRow;
import com.vandunxg.file_processing.fileimport.application.validation.ValidationIssue;

/** One record of the error report, paired with the source row it rejects. */
public record StagedReportRow(ValidationIssue issue, ParsedCustomerRow originalRow) {}
