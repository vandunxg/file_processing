# Synchronous Customer Import MVP Design

## Purpose

Deliver one end-to-end, synchronous CSV import flow for exercising the core
business rules before replacing the direct processor with the asynchronous job
workflow required by the full SRS.

This design covers upload, streamed CSV parsing, normalization, validation,
file and in-file duplicate detection, partial customer upsert, and a streamed
validation-error report.

## Explicit MVP Boundary

The approved SRS requires an upload to return `202 Accepted` and process rows
asynchronously through a `ProcessingJob`. This MVP intentionally changes that
behavior: the upload request waits for processing and returns `200 OK`.

The MVP does not introduce a worker, `ProcessingJob`, attempts, retries,
cancellation, progress polling, stale-job recovery, or retention cleanup. The
existing streaming reader, validator, duplicate tracker, customer upsert, and
report writer will be reused by the later asynchronous implementation.

## API and Authorization

`POST /api/v1/file-import/` accepts exactly one authenticated user's CSV file.
It returns the import identifier, final processing status, valid/invalid/
inserted/updated counters, and whether an error report is available.

`GET /api/v1/file-import/{fileId}/error-report` streams the final report only
when the authenticated user owns the file. A missing, foreign, or unavailable
report does not reveal another user's resource. Admin-wide access is deferred
with the broader job-management API.

## Data Model

Keep the original-file attributes in `file_import` immutable and add mutable
processing-result fields to the same record:

- `processing_status`: `PROCESSING`, `COMPLETED`, `COMPLETED_WITH_ERRORS`, or
  `FAILED`.
- `valid_rows`, `invalid_rows`, `inserted_rows`, `updated_rows`.
- `processed_rows`, `completed_at`, `error_code`, and sanitized `error_summary`.
- nullable `error_report_key`.

Add `customers` with an internal UUID primary key, globally unique
`external_id`, normalized imported fields, `last_import_file_id`, audit
timestamps, and an optimistic-lock column only if the selected JPA mapping
needs one. The database unique constraint on `external_id` is the concurrency
boundary.

The report is stored at a system-generated object-storage key. It is published
only after successful completion with at least one invalid row.

## Processing Flow

1. Validate exactly one non-empty `.csv` upload and stream it to a
   system-generated object key while computing SHA-256.
2. Reopen the stored object to validate UTF-8, the CSV header, and the
   existence of a first data record. On structural rejection, including a
   header-only file, delete the object and create no metadata.
3. Register `FileImport` with status `PROCESSING`. The unique
   `(owner_id, checksum_sha256)` index resolves concurrent duplicate uploads.
   The losing request deletes its object and returns `409 DUPLICATE_FILE` with
   the existing import identifier.
4. Reopen the stored object and consume it through `CsvValidationReader`.
   It streams records, normalizes fields, accumulates every issue for an
   invalid row, and reserves only valid `external_id` values in the existing
   PostgreSQL temporary-table tracker.
5. Write each invalid issue directly to a temporary report file together with
   the parsed row's original values. The writer emits UTF-8 BOM and
   `row_number,external_id,error_code,field,error_message,original_data`; it
   serializes `original_data` as CSV-safe JSON and never accumulates report
   rows in heap.
6. Collect at most 1,000 valid normalized rows, then atomically upsert that
   batch into `customers` with PostgreSQL `INSERT ... ON CONFLICT (external_id)
   DO UPDATE`. Update all snapshot fields, set `last_import_file_id`, and
   derive inserted versus updated counts from the database operation.
7. At EOF, flush the final batch. If invalid rows exist, stream the temporary
   report to its final object key. Update `file_import` to `COMPLETED` or
   `COMPLETED_WITH_ERRORS` with final counters, then delete the local temporary
   report. If final persistence fails, delete the unpublished final report.

No full upload, CSV, customer collection, or report is kept in memory.

## Failure Semantics

File-level structural failures before registration return the appropriate
sanitized API error and remove the stored object. Row validation failures are
not system failures: valid rows continue to commit, and every issue appears in
the final report.

If storage, parser, report, or database work fails after registration, stop
accepting batches, roll back the current batch, retain prior committed batches,
delete the temporary report, and mark the import `FAILED` with a stable,
sanitized code. Do not publish a partial report.

## Verification

Add focused tests for:

- valid synchronous upload and final counters;
- invalid header, empty file, and unsupported extension;
- concurrent or repeated same-owner checksum rejection;
- normalization, multiple validation issues on one row, and invalid-row
  counting;
- valid-only duplicate `external_id` detection;
- inserted customer, existing customer update, and empty address overwriting
  with `null`;
- final report BOM, ordered issues, and no report for an all-valid file;
- batch failure preserving earlier committed batches and a `FAILED` import.

Use existing JUnit, AssertJ, and PostgreSQL Testcontainers. Use a test object
storage adapter instead of adding dependencies.

## Async Migration

The asynchronous implementation will create `ProcessingJob` and
`ProcessingAttempt` records after successful registration, return `202`, and
move steps 4-7 into a bounded worker. It will retain this MVP's CSV reader,
validator, temporary-table deduplication, batch upsert, report writer, and
failure classification.
