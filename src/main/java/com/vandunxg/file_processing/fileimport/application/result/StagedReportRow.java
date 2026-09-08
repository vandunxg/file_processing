package com.vandunxg.file_processing.fileimport.application.result;

import com.vandunxg.file_processing.fileimport.application.validation.ParsedCustomerRow;
import com.vandunxg.file_processing.fileimport.application.validation.ValidationIssue;

/** A report record plus its stable source-order cursor. */
public record StagedReportRow(
    ValidationIssue issue, ParsedCustomerRow originalRow, int issueOrder, int source) {}
