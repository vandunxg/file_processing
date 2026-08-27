# CSV Validation And Normalization Design

<!-- prettier-ignore -->
> [!WARNING]
> **LEGACY ARCHITECTURE NOTICE — SUPERSEDED ARCHITECTURE GUIDANCE**
>
> Tài liệu này được tạo trước quyết định chuyển sang Pragmatic Modular DDD.
> Các package `adapter/*`, `port/*`, `*UseCase`, `*Port` và `*Adapter` trong tài
> liệu này mô tả legacy implementation và **không còn là architecture guidance**.
>
> This document predates the migration to Pragmatic Modular DDD. Every
> `adapter/in`, `adapter/out`, `port/in`, `port/out`, `*UseCase`,
> `*RepositoryPort`, and `*PersistenceAdapter` reference below records the
> legacy implementation **as it was actually built**. It is a historical record,
> not an instruction. Do not reproduce this layout, naming, or interface
> structure in new code or in a refactor.
>
> [`RULE.md`](../../../RULE.md) §4 is the source of truth for architecture. The
> business behavior, API contracts, and security requirements described here
> remain valid; only the structural guidance is superseded.

## Scope

Build the streaming CSV parsing, header validation, row normalization, row
validation, and bounded duplicate-`external_id` detection needed by the future
processing worker.

This slice does not create processing jobs, write customers, generate error
reports, or add a worker/executor pipeline. It exposes one validated row at a
time to its caller.

## Architecture

```text
InputStream
  -> CsvRecordReader adapter
  -> ParsedCustomerRow
  -> CustomerRowValidator
  -> DuplicateExternalIdTracker port
  -> ValidatedCustomerRow
```

- `CsvRecordReader` is an infrastructure adapter backed by Apache Commons CSV.
  It reads UTF-8 with an optional BOM, comma delimiter, and double-quote
  escaping without loading the complete file.
- `CsvRecordReader` validates the first record before rows are accepted.
- `CustomerRowValidator` normalizes and validates rows as pure application code
  with an injected `Clock` for deterministic birth-date rules.
- `DuplicateExternalIdTracker` is an outbound port. Its PostgreSQL adapter uses
  one session-scoped temporary table for one parse run.

No Spring MVC, JPA entity, S3 type, or parser-library type is exposed from the
normalizer, validator, or duplicate-tracker port.

## CSV Structure

The reader accepts UTF-8 and an optional UTF-8 BOM. The header is normalized by
removing the BOM from its first value, trimming, and lowercasing with
`Locale.ROOT`.

The reader rejects malformed UTF-8, stops after 1,000,000 nonblank data rows,
and retains at most 65,536 characters per field while parsing. Extra field
content is discarded until the field boundary so the row can still receive its
normal validation issue and later rows remain readable.

The required columns are exactly:

```text
external_id, full_name, email, phone, date_of_birth, address
```

Their order is unrestricted. Missing, repeated, or unexpected columns produce
`INVALID_CSV_HEADER`. A file with no data record after blank-line filtering is
also structurally invalid. The reader emits each record's physical starting
line number, with the header on line 1; quoted multiline records are covered by
the adapter contract and tests. An unrecoverable parser boundary failure is
`MALFORMED_CSV`, not a row validation issue.

## Row Results

`ParsedCustomerRow` preserves the original field values and physical line
number. `ValidatedCustomerRow` contains either a normalized customer row or
one or more `ValidationIssue` values. Each issue has row number, external ID
when available, error code, field, and a safe message.

Normalization happens before validation:

- `external_id`: trim.
- `full_name`: trim and collapse internal whitespace to one space.
- `email`: trim and lowercase with `Locale.ROOT`.
- `phone`: remove spaces, periods, and hyphens; `0` + nine digits becomes
  `+84` + nine digits.
- `date_of_birth`: trim before strict parsing.
- `address`: trim; an empty value becomes `null`.

Validation collects every applicable issue for one row:

- required `external_id`, `full_name`, `email`, `phone`, and `date_of_birth`;
- external ID length 1-64 and `[A-Za-z0-9_-]+`;
- full-name length 2-150;
- email length up to 254 and application email format;
- phone exactly `+84` plus nine digits after normalization;
- date `yyyy-MM-dd`, not future, and not earlier than 120 years before today;
- address length up to 500.

Use the approved error codes: `REQUIRED_FIELD`, `INVALID_EXTERNAL_ID`,
`FULL_NAME_TOO_SHORT`, `FULL_NAME_TOO_LONG`, `INVALID_EMAIL`, `INVALID_PHONE`,
`INVALID_DATE_FORMAT`, `DATE_OF_BIRTH_IN_FUTURE`, `DATE_OF_BIRTH_TOO_OLD`, and
`ADDRESS_TOO_LONG`.

## Duplicate Detection

Only a row with no field-validation issues is sent to the tracker. This ensures
the first valid occurrence is accepted and later valid occurrences receive one
`DUPLICATE_EXTERNAL_ID_IN_FILE` issue.

The PostgreSQL adapter owns one dedicated connection for the parse run and
creates:

```sql
CREATE TEMP TABLE csv_seen_external_ids (
  external_id VARCHAR(64) PRIMARY KEY
) ON COMMIT PRESERVE ROWS;
```

For each normalized valid external ID it executes `INSERT ... ON CONFLICT DO
NOTHING`. A successful insert returns first occurrence; a conflict returns a
duplicate. Closing the run drops the table before returning the connection to
the pool. This uses bounded database storage rather than an in-memory
million-entry set and needs no persistent migration.

Rows are offered to the tracker in parser order; future batching may reduce
round trips only if it preserves this first-occurrence ordering.

## Error And Resource Handling

- Header and malformed-stream errors stop parsing and are classified as file or
  system errors by the future worker/upload caller.
- Field validation errors remain values, never exceptions, and never stop the
  stream.
- The parser, input stream, JDBC statement, result set, and duplicate session
  are closed on success or failure.
- No raw row content, email, or phone is logged.

## Tests

Add focused tests for header permutations/BOM/rejections, all normalization
rules, every validation code, multi-issue rows, optional null address, strict
date boundaries, parser physical-line reporting including quoted multiline
records, quoted one-column records, malformed CSV classification, invalid-first
duplicate IDs, and duplicate valid IDs. Add a
PostgreSQL integration test proving the temporary tracker accepts the first ID,
rejects the second, and leaves no permanent table or rows.
