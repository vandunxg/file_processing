package com.vandunxg.file_processing.fileimport.application.result;

/** Final row counts after duplicate external IDs have been resolved in staging. */
public record StagingResolution(long validRows, long invalidRows) {}
