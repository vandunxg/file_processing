# Agent guide for Customer CSV File Processing Service

This file gives coding agents the project context, non-negotiable business
rules, implementation constraints, and verification requirements for the
Customer CSV File Processing Service. Read it before planning, changing code,
reviewing a pull request, or generating tests.

The service is an approved-for-development backend that imports large customer
CSV files. It stores the original file, prevents duplicate imports, processes
rows asynchronously, upserts customer records, tracks job progress, publishes
validation error reports, and supports retry, cancellation, recovery, audit,
and operational monitoring.

<!-- prettier-ignore -->
> [!IMPORTANT]
> The supplied BA and SRS documents define approved behavior. You may choose
> packages, libraries, physical schemas, and implementation details, but you
> must not change business behavior without an explicit change request.

## Source of truth

Use the following documents as the authoritative requirements set. Read the
focused documents first and use the master SRS to resolve missing context.

1. [`00-README.md`](./docs/requirement/00-README.md) explains the handoff package and reading
   order.
2. [`01-BRD-business-context.md`](./docs/requirement/01-BRD-business-context.md) defines the
   problem, goals, scope, stakeholders, and actors.
3. [`02-domain-model-and-business-rules.md`](./docs/requirement/02-domain-model-and-business-rules.md)
   defines aggregates, invariants, state transitions, CSV rules, transaction
   semantics, and audit requirements.
4. [`03-functional-specification.md`](./docs/requirement/03-functional-specification.md) defines
   the five features, use cases, API behavior, authorization rules, acceptance
   criteria, and error catalog.
5. [`04-non-functional-requirements.md`](./docs/requirement/04-non-functional-requirements.md)
   defines performance, memory, reliability, security, observability,
   shutdown, recovery, and retention constraints.
6. [`05-test-and-developer-handover.md`](./docs/requirement/05-test-and-developer-handover.md)
   defines acceptance scenarios, implementation phases, required deliverables,
   review questions, and the Definition of Done.
7. [`requirements-traceability-matrix.csv`](./docs/requirement/requirements-traceability-matrix.csv)
   maps requirements to use cases, acceptance criteria, and test types.
8. [`99-master-BA-SRS.md`](./docs/requirement/99-master-BA-SRS.md) is the consolidated reference.

When two focused documents appear inconsistent, compare the same section in
`99-master-BA-SRS.md`. Do not silently choose a behavior. Record the conflict
and request a business decision when the intended behavior remains unclear.

## Project objective

Replace a manual CSV import process with one centralized backend lifecycle:

```text
Receive file -> store file -> detect duplicate -> create job -> process rows
-> expose progress -> store result -> publish error report -> retry or cancel
```

The first release must achieve these outcomes:

- Import every valid-template file without a technician running a script.
- Create no additional job when the same owner uploads the same file content.
- Make every accepted job queryable after upload.
- Include every row-level validation issue in the error report.
- Prevent jobs from remaining in `PROCESSING` forever after a crash or restart.
- Prevent complete sensitive customer data from appearing in application logs.

## Scope boundaries

The first release contains exactly five feature groups:

1. Upload and register one customer CSV file.
2. Detect duplicate file content and create one canonical processing job.
3. Parse, normalize, validate, and upsert customer data asynchronously.
4. List jobs and expose job detail, progress, history, and results.
5. Download a final error report, retry eligible jobs, and cancel eligible jobs.

The release also requires JWT authentication, backend RBAC, PostgreSQL, MinIO,
Redis or Redisson coordination, audit logging, metrics, health checks,
structured logging, schema migrations, Docker Compose, and automated tests.

Do not add the following features unless a new requirement explicitly requests
them:

- A complete frontend.
- Excel, JSON, XML, PDF, image, or OCR import.
- Multiple files in one upload request.
- Automatic ingestion from email, SFTP, or cloud drives.
- Kafka, RabbitMQ, Event Sourcing, CQRS frameworks, or workflow engines.
- Multi-tenant billing or enterprise antivirus.
- Exact byte-level resume after a process crash.
- In-app CSV editing or automatic email notification.

## Required technology baseline

Build a modular monolith using **Pragmatic Modular DDD**: package by business
module, with `api` / `application` / `domain` / `infrastructure` layers inside
each module. [`RULE.md` §4](./RULE.md) is the normative definition of that
architecture and of every naming and dependency rule. Use the following
technology baseline unless the repository already contains an approved choice:

- Java 21 or newer.
- Spring Boot 4.x.
- Maven.
- PostgreSQL.
- MinIO or another S3-compatible implementation that preserves the required
  behavior.
- Redis with Redisson or an equivalent approved coordination layer.
- Flyway or Liquibase for schema migrations.
- Docker Compose for the complete local environment.
- OpenAPI for the public API.

Do not introduce native image support in the first release. Do not create an
interface for every class. Keep controllers free of business logic.

## Architectural boundaries

<!-- prettier-ignore -->
> [!IMPORTANT]
> **Architecture target:** Pragmatic Modular DDD, defined normatively in
> [`RULE.md` §4](./RULE.md).
>
> **Legacy implementation:** parts of `src/main/java` still use a Hexagonal
> `adapter/in`, `adapter/out`, `application/port/in`, `application/port/out`
> layout with `*UseCase`, `*RepositoryPort`, and `*PersistenceAdapter` types.
> That code is being migrated. It is not architecture guidance, and no new
> code may extend it.
>
> This file defines **business behavior**. When a structural question arises,
> `RULE.md` decides it. When `RULE.md` appears to conflict with a business
> rule stated here, the business rule wins.

Keep two logical bounded contexts inside one application. Each is a business
module with its own `api`, `application`, `domain`, and `infrastructure`.

### File Import context

This context owns the lifecycle of files and jobs. It manages `ImportFile`,
`ProcessingJob`, `ProcessingAttempt`, validation results, progress, reports,
retry, cancellation, recovery, and retention.

### Customer context

This context owns `Customer`, its business identity `externalId`, normalized
customer fields, and the most recent import job that changed the record.

Do not collapse both contexts into one large service class. Cross-context work
goes through the other context's application service, or through a domain or
application event when asynchronous decoupling has real value. One context
**MUST NOT** read or write the other context's persistence model or persistence
implementation. Avoid ceremonial abstractions that do not protect a rule or
support a real substitution.

## Actors and authorization

Enforce authorization in the backend and in data access queries. Never depend
on frontend visibility for access control.

### Operator

An Operator can upload a file and can list, view, download, retry, or cancel
only resources that the Operator owns.

For a resource owned by another user, return `404`, not `403`, to avoid exposing
that the resource exists. An Operator cannot view a system-wide dashboard,
technical stack trace, full technical error detail, another user's job, or
another user's audit history.

### Admin

An Admin has all Operator capabilities and can also list all jobs, filter by
owner, inspect sanitized technical error information, retry or cancel any
eligible job, and view audit history.

An Admin cannot bypass the retry-attempt limit in the first release.

### Processing worker

The Processing Worker is an internal actor. It claims queued jobs, reads stored
files, processes logical batches, updates progress and heartbeat, creates error
reports, and moves jobs to terminal states.

The worker has no public API and does not use an end-user JWT.

## Core domain model

The implementation may adjust persistence details, but it must preserve the
following domain responsibilities and invariants.

### ImportFile aggregate

`ImportFile` represents an immutable original object that has been safely
stored and registered.

It must preserve these rules:

- Use a system-generated `storageKey`; never derive the storage path directly
  from a user-supplied filename.
- Keep the original filename only for display.
- Compute a lowercase, 64-character SHA-256 checksum before treating the file
  as valid.
- Enforce logical uniqueness on `(ownerId, checksumSha256)`.
- Create metadata only after the object has been stored successfully.
- Never mutate the content of a registered import file.
- Store the object size, detected content type, creation time, and retention
  deadline.

### ProcessingJob aggregate

`ProcessingJob` owns the state machine, current progress, counters, retry and
cancel decisions, heartbeat, and final result.

It must preserve these invariants:

- Only a `QUEUED` job can be claimed.
- At most one attempt can be `RUNNING` for a job.
- Progress cannot decrease within one attempt.
- `processedRows = validRows + invalidRows`.
- `insertedRows + updatedRows <= validRows`.
- A terminal job cannot be cancelled.
- Retry creates a new attempt and preserves old attempt history.
- `COMPLETED` has zero invalid rows.
- `COMPLETED_WITH_ERRORS` has at least one invalid row.
- `FAILED` represents a system failure, not row validation errors.
- `CANCELLED` is set only after the worker stops at a safe point, except for a
  queued job that is cancelled before claim.

### ProcessingAttempt entity

`ProcessingAttempt` records every execution of a job. Attempt history is
append-only from the business perspective and must not be overwritten.

Each attempt must include an increasing `attemptNumber`, trigger, status,
timestamps, counters, and sanitized error information. Supported triggers are
`INITIAL`, `USER_RETRY`, `ADMIN_RETRY`, and `RECOVERY`.

User- or Admin-triggered retries are limited to three attempts. Operation-level
retries do not increment the attempt number.

### Customer aggregate

`Customer` is upserted by the globally unique business key `externalId`.

It must preserve these rules:

- Insert a new customer when `externalId` does not exist.
- Update all imported snapshot fields when `externalId` already exists.
- Count an existing record as updated even when the new values are identical.
- Treat an empty imported address as `null` and overwrite the old address.
- Never change the internal customer ID during import.
- Never delete customers merely because a later file omits them.
- Record the latest import job responsible for the customer state.

Concurrent jobs that update the same `externalId` use record-level
last-write-wins based on actual database commit ordering. Use atomic database
upsert or an equivalent transaction strategy that handles concurrent insert
and update correctly.

## Job state machine

Implement state transitions through domain behavior or a tightly controlled
application boundary. Do not set arbitrary status values directly from
controllers or unrelated services.

The allowed transitions are:

```text
[initial] -> QUEUED
QUEUED -> PROCESSING
QUEUED -> CANCELLED
PROCESSING -> CANCELLATION_REQUESTED
CANCELLATION_REQUESTED -> CANCELLED
PROCESSING -> COMPLETED
PROCESSING -> COMPLETED_WITH_ERRORS
PROCESSING -> FAILED
CANCELLATION_REQUESTED -> FAILED
FAILED -> QUEUED          through approved retry
CANCELLED -> QUEUED       through approved retry
```

The following transitions are forbidden:

- `COMPLETED` or `COMPLETED_WITH_ERRORS` to retry states.
- `FAILED` directly to `PROCESSING`.
- `PROCESSING` back to `QUEUED` without ending the active attempt.
- Cancellation of `COMPLETED`, `COMPLETED_WITH_ERRORS`, or `FAILED`.
- Two workers changing the same job from `QUEUED` to `PROCESSING`.

Treat `COMPLETED`, `COMPLETED_WITH_ERRORS`, `FAILED`, and `CANCELLED` as terminal
for the current attempt. A retry of `FAILED` or `CANCELLED` keeps the same job ID
and creates a new attempt when the worker claims the job.

## CSV contract

Accept one CSV file with UTF-8 encoding. Accept a UTF-8 BOM, use comma as the
delimiter, and use double quote as the quote character.

The required columns are:

```csv
external_id,full_name,email,phone,date_of_birth,address
```

Apply these structural rules:

- Compare header names case-insensitively after trimming.
- Accept any order of the six required columns.
- Reject missing columns.
- Reject unexpected columns in the first release.
- Reject a file that contains only the header and no data row.
- Ignore blank lines and do not count them as data rows.
- Report physical line numbers, with the header on line 1.
- Accept at most 1,000,000 data rows.
- Accept at most 500 MB by default; make the value configurable.
- Require a `.csv` filename, but do not trust the extension or client MIME type
  as proof of content.

### Field normalization and validation

Apply normalization before field validation where the rule requires it.

`external_id` must be present, trimmed, 1 to 64 characters, and contain only
letters, digits, hyphens, or underscores.

`full_name` must be present, trimmed, have repeated internal whitespace reduced
to one space, and contain 2 to 150 characters after normalization.

`email` must be present, trimmed, lowercased, at most 254 characters, and pass
the application's approved email format check. Do not attempt delivery or DNS
verification.

`phone` must be present. Remove spaces, periods, and hyphens. Accept a Vietnam
number beginning with `0` or `+84`, then normalize it to `+84` followed by nine
digits. Do not verify whether the number is active.

`date_of_birth` must be present, use `yyyy-MM-dd`, not be in the future, and not
be earlier than 120 years before the current date.

`address` is optional. Trim it, limit it to 500 characters, and store an empty
value as `null`.

### Duplicate external IDs within one file

For repeated `external_id` values in one file, process the first valid
occurrence and reject every later occurrence with
`DUPLICATE_EXTERNAL_ID_IN_FILE`.

The detection strategy must stay within the memory target. Do not assume a
one-million-entry `HashSet<String>` is acceptable. Use a temporary database
constraint, a disk-backed structure, or provide a benchmark that proves an
in-memory approach stays within the 512 MB demo heap with sufficient headroom.

### Multiple issues on one row

Collect every validation issue for a row. Write one error-report record per
issue, but increase `invalidRows` only once for that original row.

Do not send invalid rows to the customer upsert transaction.

## Upload and duplicate-control workflow

The upload API returns success only after the original object is safely stored,
the checksum is known, the header is valid, and the `ImportFile` plus initial
`ProcessingJob` are committed.

Follow this sequence:

1. Authenticate the caller and enforce upload permission.
2. Validate that the request contains exactly one file.
3. Enforce empty-file, size, filename, extension, and content checks.
4. Stream the upload to a temporary storage key while computing SHA-256.
5. Open the streamed object or stream prefix safely to validate UTF-8 and the
   CSV header without loading the entire file.
6. Coordinate duplicate detection with a distributed lock keyed by
   `ownerId + checksum`.
7. Use the database unique constraint on `(owner_id, checksum_sha256)` as the
   final correctness boundary.
8. Promote or copy the temporary object to a system-generated canonical key.
9. Create the file metadata and initial `QUEUED` job in the appropriate
   transaction.
10. Write the required audit events.
11. Return `202 Accepted` with the file and job summary.

The upload endpoint must not wait for row processing.

If the file is a duplicate for the same owner, delete the current temporary
object, create no metadata or job, and return `409 DUPLICATE_FILE` with the
existing file ID, job ID, job status, and upload time.

The duplicate rule applies even when the existing job is `FAILED` or
`CANCELLED`. The caller must use retry rather than upload identical content
again.

Two different owners may upload identical content and receive separate files
and jobs.

Redis or Redisson is a coordination aid, not the correctness boundary. If the
lock expires or Redis is unavailable, the database constraint must still
prevent duplicates. Convert the losing unique-constraint race into a clean
`409`, never a `500`, and clean the losing temporary object.

## Asynchronous processing workflow

Process each file as a stream. Never read the full file, all rows, or the full
error report into heap memory.

Follow this conceptual flow:

1. Select queued jobs in ascending creation order.
2. Claim one job atomically.
3. Move the job to `PROCESSING`, create a `RUNNING` attempt, set `startedAt` and
   `heartbeatAt`, and write `JOB_STARTED`.
4. Open the original object as a stream from MinIO.
5. Parse records sequentially without splitting the CSV by byte offsets.
6. Normalize and validate each complete CSV record.
7. Detect duplicate `external_id` values within the same file.
8. Build logical batches after parsing complete records.
9. Execute bounded validation, transformation, database, and report work.
10. Upsert valid rows in one transaction per logical batch.
11. Stream invalid-row issues to a temporary error report.
12. Merge concurrent batch results in batch order so the report remains sorted
    by physical row number.
13. Update progress and heartbeat at the required cadence.
14. Check cancellation between logical batches at a safe point.
15. At EOF, flush work, finalize counters, and publish a final report only when
    the job ends as `COMPLETED_WITH_ERRORS`.
16. Close resources and persist the terminal attempt and job state.

Use a default logical batch size of 1,000 rows and a default maximum of four
in-flight batches. Both values must be configurable.

Use bounded queues or explicit permits. When capacity is full, the parser or
producer must wait or apply backpressure. Do not create unbounded tasks,
threads, futures, or queues.

Use platform threads for CPU-bound validation and transformation. The default
CPU pool size may match the number of available processors. Use virtual
threads for blocking I/O only when concurrent operations are still bounded by
a semaphore or equivalent limit. Never use `ForkJoinPool.commonPool()` for the
pipeline.

## Progress and counters

Do not update progress after every row. Update progress when either condition
is true:

- At least 5,000 additional rows have completed since the last update.
- At least two seconds have elapsed since the last update.

During streaming, `totalRows` and `progressPercent` may be `null`. Always expose
`processedRows` and the latest heartbeat. After EOF, set the final total and a
final progress value of 100 percent.

A progress response must include status, processed rows, valid rows, invalid
rows, inserted rows, updated rows, total rows when known, progress percent when
known, `startedAt`, and `heartbeatAt`.

Read endpoints may be eventually consistent by at most two seconds. A terminal
response must contain final counters.

## Transaction and consistency rules

Keep the upload stream outside the database transaction. Store the file first,
then commit file metadata and the initial job.

Use these transaction boundaries:

- One transaction for the registration of `ImportFile` and its initial job.
- One transaction per logical customer-upsert batch.
- Progress updates outside the business batch transaction, or isolated so they
  cannot roll back committed customer data.
- One transaction for finalizing the job and attempt.
- Consistent audit persistence for important state transitions.

The import model uses partial commit with idempotent retry, not all-or-nothing
rollback.

If batch 5 fails after batches 1 through 4 commit, roll back batch 5, keep the
first four batches, and move the job to `FAILED` with an actionable system error
code. A later retry reads the file from the beginning. Atomic upsert by
`externalId` prevents duplicate customers, and the new attempt recalculates its
own counters from zero.

## Validation errors and system errors

Treat row validation failures as expected business outcomes. They do not stop
the job, do not trigger operation retry, and do not change the job to `FAILED`.
A file with one or more invalid rows ends as `COMPLETED_WITH_ERRORS` and has a
final published report.

Treat infrastructure, parser, concurrency, timeout, and unexpected runtime
failures as system errors. Examples include storage failure, database failure,
executor rejection, processing timeout, malformed CSV that prevents record
boundary recovery, and error-report generation failure.

On an unrecoverable system error:

1. Stop creating new batches.
2. Cancel or finish related work according to the bounded pipeline policy.
3. Roll back the current failing batch.
4. Close streams and temporary report resources.
5. Mark the active attempt `FAILED`.
6. Mark the job `FAILED` with a stable code and sanitized summary.
7. Keep previously committed batches.
8. Keep the original file for eligible retry.
9. Write `JOB_FAILED`.

A malformed record that prevents the parser from finding the next record
boundary is `MALFORMED_CSV`, not a row validation issue.

## Retry and timeout policy

Apply operation-level retry only to transient operations. Use at most three
retries with backoff of approximately one, two, and four seconds, plus jitter.

Default operation timeouts are 30 seconds for storage read, database batch, and
report write operations. Make them configurable.

Do not retry validation errors, authorization failures from storage, invalid
CSV structure, or classified business uniqueness errors.

A future timeout alone does not stop underlying work. When a timeout occurs,
also cancel, interrupt, close, or otherwise terminate the underlying operation
where the client library supports it. Always record timeout metrics, and never
mark a timed-out job as completed.

## Error report contract

Publish a final report only for `COMPLETED_WITH_ERRORS`. A partial report from a
`FAILED` or `CANCELLED` attempt is not downloadable as a final business report.

Use this column order:

```csv
row_number,external_id,error_code,field,error_message,original_data
```

The report must:

- Use UTF-8 with BOM for spreadsheet compatibility.
- Preserve ascending physical row order.
- Keep all issues for one row adjacent.
- Serialize `original_data` safely as JSON text or a CSV-safe equivalent.
- Include controlled business data such as email because the report is a
  protected output, while application logs still mask email and phone.
- Be streamed during generation rather than accumulated in memory.
- Be exposed only to the owner or an Admin.

For `COMPLETED`, return `409 REPORT_NOT_AVAILABLE`. For an expired final report,
return `410 REPORT_EXPIRED`.

Use an authenticated response stream or a signed URL that expires in at most
five minutes. Keep the MinIO bucket private.

## Retry behavior

Allow retry only when the job is `FAILED` or `CANCELLED`, the original object
still exists, retention has not expired, no attempt is running, and the
user-triggered attempt limit has not been exceeded.

Retry must:

- Keep the same job ID.
- Preserve all previous attempt history.
- Reset current progress and counters for the next attempt.
- Clear current runtime error and timing fields where appropriate while keeping
  the old values in attempt history.
- Move the job to `QUEUED`.
- Create the next attempt only when a worker claims the job.
- Write `JOB_RETRY_REQUESTED`.
- Return `202 Accepted`.

Return `409 JOB_NOT_RETRYABLE` for successful terminal jobs and
`409 RETRY_LIMIT_EXCEEDED` after the permitted retries. Return
`410 ORIGINAL_FILE_EXPIRED` when retry is impossible because the original
object expired.

## Cancellation behavior

Cancellation is cooperative. Never kill a thread using deprecated or unsafe
APIs.

For a `QUEUED` job, atomically change `QUEUED` to `CANCELLED`. A worker must not
claim the job after that transition.

For a `PROCESSING` job, atomically change the status to
`CANCELLATION_REQUESTED`, return `202`, stop accepting new batches, let the
current transaction finish, and stop at the next safe point between batches.
Then discard or keep the temporary report as an internal object, do not publish
it, and move the attempt and job to `CANCELLED`.

Committed batches remain committed. A later retry starts from the beginning.

Cancel requests are idempotent for `CANCELLATION_REQUESTED` and `CANCELLED`.
Return the current state without creating duplicate audit effects. Return
`409 JOB_NOT_CANCELLABLE` for successful terminal jobs.

## API capabilities

The exact URI design is flexible, but the public API must expose these
capabilities:

- Login.
- Upload one customer CSV file.
- List jobs with filters and pagination.
- Read job detail.
- Read job progress.
- Download the final error report.
- Retry a failed or cancelled job.
- Cancel a queued or processing job.
- Read audit history for Admin users.

Job listing must support status, original-filename keyword, creation-time
range, and Admin-only owner filters. Default sorting is `createdAt DESC`,
default page size is 20, and maximum page size is 100.

Job detail must include file metadata, owner summary, job status, counters,
progress, current attempt, attempt history, timestamps, report availability,
sanitized error summary, Admin-only technical code, and computed available
actions.

Never return storage credentials, internal filesystem paths, JWTs, raw stack
traces, or complete original customer rows.

## Standard API errors

Support at least these API and action codes:

- `FILE_REQUIRED` -> `400`.
- `ONLY_ONE_FILE_ALLOWED` -> `400`.
- `EMPTY_FILE` -> `400`.
- `FILE_SIZE_EXCEEDED` -> `413`.
- `UNSUPPORTED_FILE_TYPE` -> `415`.
- `INVALID_CSV_HEADER` -> `422`.
- `DUPLICATE_FILE` -> `409`.
- `STORAGE_UNAVAILABLE` -> `503`.
- `JOB_NOT_FOUND` -> `404`.
- `JOB_NOT_RETRYABLE` -> `409`.
- `JOB_NOT_CANCELLABLE` -> `409`.
- `RETRY_LIMIT_EXCEEDED` -> `409`.
- `ORIGINAL_FILE_EXPIRED` -> `410`.
- `REPORT_NOT_AVAILABLE` -> `409`.
- `REPORT_EXPIRED` -> `410`.

Support at least these processing system codes:

- `ORIGINAL_FILE_NOT_FOUND`.
- `MALFORMED_CSV`.
- `STORAGE_READ_TIMEOUT`.
- `STORAGE_WRITE_TIMEOUT`.
- `DATABASE_UNAVAILABLE`.
- `DATABASE_BATCH_FAILED`.
- `PROCESSING_TIMEOUT`.
- `EXECUTOR_OVERLOADED`.
- `REPORT_GENERATION_FAILED`.
- `INTERNAL_PROCESSING_ERROR`.
- `WORKER_LOST`.

Support at least these row validation codes:

- `REQUIRED_FIELD`.
- `INVALID_EXTERNAL_ID`.
- `FULL_NAME_TOO_SHORT`.
- `FULL_NAME_TOO_LONG`.
- `INVALID_EMAIL`.
- `INVALID_PHONE`.
- `INVALID_DATE_FORMAT`.
- `DATE_OF_BIRTH_IN_FUTURE`.
- `DATE_OF_BIRTH_TOO_OLD`.
- `ADDRESS_TOO_LONG`.
- `DUPLICATE_EXTERNAL_ID_IN_FILE`.

Do not expose raw exception messages or stack traces in API responses.

## Persistence expectations

The physical schema may vary, but it must support the following logical model
and constraints.

### import_files

Store ID, owner, display filename, canonical storage key, SHA-256 checksum,
size, detected content type, retention deadline, creation time, and optional
optimistic-lock version.

Enforce unique `(owner_id, checksum_sha256)` and unique canonical storage key.

### processing_jobs

Store the file reference, owner, status, all counters, optional total and
progress percentage, current attempt, cancellation signal, sanitized error
fields, final report key, lifecycle timestamps, heartbeat, optimistic-lock
version, and audit timestamps.

Provide indexes for owner and descending creation time, status and creation
time, heartbeat, and file reference.

### processing_attempts

Enforce unique `(job_id, attempt_number)`. Index by job plus descending attempt,
and by status plus start time.

### customers

Enforce a unique `external_id`. Store normalized fields, the last import job,
audit timestamps, and an optimistic-lock field if the chosen upsert design
needs it.

### audit_events

Store actor type and ID, action, resource type and ID, previous and new status,
sanitized metadata, occurrence time, and trace ID. Treat events as append-only
at the application level.

Do not store every `ValidationIssue` in PostgreSQL. Store final invalid counts,
the report key, and optionally a small aggregate summary. Keep detailed issues
in the protected report object.

## Performance and memory requirements

Support a 500 MB file and 1,000,000 data rows with a target demo heap of
512 MB.

The implementation must:

- Stream upload and processing.
- Avoid `MultipartFile.getBytes()`, `readAllBytes()`, `readAllLines()`, and
  collecting every row before processing.
- Stream the error report.
- Keep batch size and in-flight count configurable.
- Avoid progress writes per row.
- Avoid unbounded executors, queues, and retry loops.
- Return upload success after storage and job creation, not after processing.
- Keep list and detail API p95 below 500 ms in a reasonable local test dataset,
  excluding external network latency.

Produce a benchmark report that states machine configuration, heap size, file
size, row count, rows per second, megabytes per second, and observed memory
behavior. Benchmark goals are evidence for the implementation, not a production
SLA.

## Reliability, recovery, and maintenance

Persist file metadata and job state so a service restart does not erase them.
A processing or cancellation-requested job must update `heartbeatAt`.

Run stale-job recovery every minute. A job is stale when its status is
`PROCESSING` or `CANCELLATION_REQUESTED` and its heartbeat is older than two
minutes.

Recovery must:

1. Lock the job.
2. Recheck status and heartbeat.
3. Mark the running attempt `FAILED`.
4. Mark the job `FAILED`.
5. Set `errorCode = WORKER_LOST`.
6. Write an audit event.
7. Avoid automatically requeueing the job.
8. Let the owner or an Admin request retry.

Delete temporary upload objects older than 24 hours. Make cleanup idempotent.
Log and count cleanup failures without stopping the service.

Delete original files and final error reports after 30 days. Keep job metadata
and audit history for at least 180 days. Never delete the original object for a
job that is `QUEUED`, `PROCESSING`, or `CANCELLATION_REQUESTED`.

If an object disappears before metadata retention ends, keep the job detail and
return the correct retry or download error instead of deleting history.

## Graceful shutdown

On `SIGTERM`, perform this sequence:

1. Change readiness to not ready.
2. Stop claiming new jobs.
3. Stop scheduling new processing work.
4. Signal active workers to stop cooperatively.
5. Wait up to 30 seconds for active work.
6. Let an active transaction or batch reach a safe point where possible.
7. Preserve enough status and heartbeat information for stale recovery.
8. Shut down executors and await termination.
9. Exit after the timeout and let recovery mark abandoned work as stale.

Do not promise that every job completes within the shutdown window.

## Security requirements

Treat security rules as mandatory implementation behavior.

- Load JWT signing material from environment variables or a secret manager.
- Hash passwords with BCrypt, Argon2, or another approved adaptive hash. Never
  store plaintext passwords.
- Enforce RBAC in the backend.
- Apply upload size limits at the reverse proxy and application layers.
- Prevent path traversal and never trust user filenames.
- Detect content rather than trusting client MIME type.
- Keep the MinIO bucket private.
- Limit signed download URLs to five minutes or less.
- Never log JWTs, passwords, storage credentials, or complete customer rows.
- Mask email and phone in logs.
- Restrict reports containing PII to the owner or an Admin.
- Return sanitized errors without stack traces.
- Add dependency and secret scanning to CI when possible.

## Observability requirements

Every processing log must support correlation and investigation without
exposing customer data.

Include these fields where applicable:

- `traceId`.
- `jobId`.
- `fileId`.
- `attemptNumber`.
- `status`.
- `errorCode`.

Emit structured events for upload start and completion, duplicate detection,
job claim, sampled or debug batch completion, job completion, job failure,
cancellation request, job cancellation, retry request, and stale-job
detection.

Provide these counters:

- `file_upload_total{result}`.
- `file_upload_bytes_total`.
- `processing_job_total{status}`.
- `processing_rows_total{result}`.
- `processing_retry_total{operation}`.
- `processing_timeout_total{operation}`.
- `duplicate_upload_total`.
- `job_cancel_total{result}`.

Provide these timers:

- `file_upload_duration`.
- `job_processing_duration`.
- `batch_processing_duration`.
- `storage_operation_duration{operation}`.
- `database_batch_duration`.

Provide gauges for queued jobs, processing jobs, executor active tasks, executor
queue depth where applicable, and current in-flight batches.

Never use job ID, file ID, user ID, filename, or error message as a metric label.
These values create high-cardinality series.

Liveness must show whether the process is alive and must not depend on
PostgreSQL, Redis, or MinIO. Readiness must reflect whether the dependencies
required for new requests are available. Restrict sensitive health detail by
environment or role.

## Audit requirements

Write append-only audit events for at least these actions:

- `FILE_UPLOADED`.
- `DUPLICATE_UPLOAD_REJECTED`.
- `JOB_CREATED`.
- `JOB_STARTED`.
- `JOB_COMPLETED`.
- `JOB_FAILED`.
- `JOB_RETRY_REQUESTED`.
- `JOB_CANCELLATION_REQUESTED`.
- `JOB_CANCELLED`.
- `ERROR_REPORT_DOWNLOADED`.
- `STALE_JOB_MARKED_FAILED`.
- `FILE_DELETED_BY_RETENTION`.
- `REPORT_DELETED_BY_RETENTION`.

Each event must include actor, resource identifiers, occurrence time, trace ID,
previous and new status when applicable, and safe metadata. Never place a
secret or complete customer row in audit metadata.

## Required ADRs and documentation

Create and maintain ADRs for these decisions:

1. Spring MVC multipart versus WebFlux streaming.
2. Duplicate upload coordination and the database correctness boundary.
3. Logical batching, bounded concurrency, and backpressure.
4. Partial commit and idempotent whole-job retry.
5. Resource-bounded duplicate `external_id` detection within a file.
6. Graceful shutdown and stale-job recovery.

Also deliver a root README, OpenAPI definition, ERD, upload sequence diagram,
processing sequence diagram, state-machine diagram, operational runbooks, and a
benchmark report.

Runbooks must cover stale jobs, storage unavailability, database
unavailability, and report-download failures.

## Testing and traceability

Use [`requirements-traceability-matrix.csv`](./docs/requirement/requirements-traceability-matrix.csv)
to connect implementation and tests to the approved requirements.

At minimum, automate these acceptance scenarios:

- Valid upload returns `202` with a `QUEUED` job.
- Invalid header returns `422`.
- Two concurrent duplicate uploads produce one canonical job and one `409`.
- An all-valid file ends as `COMPLETED`.
- A file with row validation issues ends as `COMPLETED_WITH_ERRORS` and has a
  final report.
- Multiple issues on one row create multiple report entries but one invalid-row
  count.
- A later duplicate `external_id` in one file is rejected.
- An existing customer is updated rather than duplicated.
- A database failure rolls back the current batch but preserves prior batches.
- Retry after partial commit does not create duplicate customers.
- Cancelling a queued job immediately prevents claim.
- Cancelling a processing job stops at a safe point.
- Killing the service during processing leads to `FAILED/WORKER_LOST` through
  recovery.
- An Operator cannot discover another user's job.
- Downloading a report for `COMPLETED` returns `409`.
- Downloading an expired report returns `410`.
- A 500 MB upload and processing path does not load the full file into heap.
- Executor saturation applies backpressure.
- Storage timeout uses bounded retry and increments timeout metrics.
- Graceful shutdown stops claim and terminates executors within policy.

The test suite must include:

- Unit tests for state transitions.
- Unit tests for normalization and validation.
- PostgreSQL integration tests with Testcontainers.
- MinIO integration tests.
- JWT and authorization tests.
- Duplicate-upload concurrency tests.
- Atomic job-claim concurrency tests.
- Retry, timeout, cancellation, recovery, and retention tests.
- Large-file and memory tests.

## Recommended implementation sequence

Implement correctness before concurrency optimization.

### Phase 1: Sequential happy path

Build authentication, streaming upload, MinIO persistence, metadata and job
creation, sequential CSV parsing, normalization, validation, batch upsert, and
final job results. Prove the small-file path with one worker first.

### Phase 2: Error paths

Add error reports, stable error codes, operation retry, system-failure handling,
audit events, and complete authorization.

### Phase 3: Bounded concurrency

Add duplicate-race protection, atomic job claim, logical-batch pipeline,
separate CPU and I/O execution, bounded in-flight work, timeout handling, and
cancellation safe points.

### Phase 4: Production readiness

Add graceful shutdown, stale recovery, retention cleanup, metrics, health
checks, structured logging, benchmark evidence, and runbooks.

Do not begin with a complex concurrent pipeline before the sequential business
path and its tests are correct.

## Coding-agent working rules

Apply these rules to every code change.

1. Read the relevant requirement section and acceptance criteria before
   changing behavior.
2. State which requirement IDs, use cases, or acceptance criteria the change
   implements.
3. Preserve domain invariants in domain or application code, not only in API
   validation.
4. Use database constraints as the final guard for uniqueness and races.
5. Add a migration for every persisted schema change.
6. Add or update automated tests for every behavior change.
7. Keep streaming and bounded-resource behavior visible in code and tests.
8. Close streams, temporary objects, futures, executors, and client resources
   on success, failure, cancellation, and timeout.
9. Preserve sanitized errors, trace correlation, metrics, and audit effects.
10. Avoid speculative frameworks, abstractions, or services outside the
    approved scope.
11. Follow the module layout, dependency direction, naming, and interface rules
    in `RULE.md` §4 to §6. Do not extend the legacy Hexagonal structure, even
    inside a module that still contains it.
12. Update OpenAPI, diagrams, ADRs, runbooks, and this file when a technical
    decision changes how agents must work.
13. Do not silently reinterpret an approved business rule. Raise a change
    request when the requested implementation conflicts with the specification.

## Patterns to reject during review

Reject or revise code that uses any of these patterns without measured and
approved justification:

- `MultipartFile.getBytes()`, `Files.readAllBytes()`, `readAllLines()`, or full
  file collection.
- Loading every parsed row or every report entry into one collection.
- An unbounded executor, queue, retry loop, or set of futures.
- Dividing CSV work by arbitrary byte ranges.
- Using Redis locks as the only duplicate-prevention guarantee.
- Updating progress for every row.
- Publishing partial reports from failed or cancelled attempts.
- Retrying validation errors.
- Treating validation issues as job failures.
- Reusing one attempt record and overwriting history.
- Creating a new job ID on retry.
- Returning `403` for another user's guessed job ID.
- Exposing stack traces, secrets, full customer rows, or high-cardinality metric
  labels.
- Killing worker threads directly for cancellation.
- Assuming `CompletableFuture.orTimeout()` stops the underlying operation.
- Adding Kafka, CQRS, Event Sourcing, microservices, or an interface for every
  class without an approved requirement.
- New `adapter/in`, `adapter/out`, `port/in`, or `port/out` packages, or new
  `*UseCase`, `*RepositoryPort`, `*StoragePort`, or `*PersistenceAdapter` types.
  These belong to the legacy Hexagonal layout being migrated away.
- One context reading the other context's persistence model, Spring Data
  repository, or persistence implementation.
- A repository for an entity that lives inside another aggregate, for example a
  `ProcessingAttemptRepository` when `ProcessingAttempt` belongs to the
  `ProcessingJob` aggregate.

## Definition of Done

A feature or project is done only when all applicable acceptance criteria pass
and the implementation preserves the following project-wide guarantees:

- Every business endpoint uses JWT and RBAC.
- No upload or processing path loads the complete file into memory.
- Concurrent duplicate uploads create one canonical file and job.
- Invalid job-state transitions are impossible through public application
  behavior.
- Validation errors complete with a final report instead of failing the job.
- System errors produce a stable, investigable error code.
- Retry does not duplicate customers.
- Cancellation works for queued and processing jobs.
- Restart and crash recovery prevent permanently stuck processing jobs.
- Logs correlate by trace and job without exposing complete PII.
- Metrics avoid high-cardinality labels.
- Health checks represent the correct dependency model.
- Database migrations build the schema automatically.
- Docker Compose starts the complete local environment.
- Integration tests are repeatable.
- A new developer can clone and run the project from the README without code
  changes.
- A one-million-row benchmark records throughput and memory evidence.
- The Tech Lead accepts all required ADRs.
- The implementation does not add unnecessary messaging, CQRS, Event Sourcing,
  or abstraction layers.
- Every module added or changed follows the `api` / `application` / `domain` /
  `infrastructure` layout and the dependency rules in `RULE.md` §4.

## Review focus

When reviewing a design or pull request, answer these questions with code,
test, migration, or benchmark evidence:

- How does upload avoid copying the full file into heap?
- How are temporary objects cleaned if storage succeeds but database commit
  fails?
- How does the database prevent duplicates when Redis coordination fails?
- Why does duplicate identity include `ownerId`?
- Where does the pipeline apply backpressure?
- Why is CPU work separated from bounded blocking I/O work?
- How does a timeout stop or close the underlying operation?
- What remains committed when a later batch fails?
- Why does retry from the beginning keep customers and counters correct?
- How are concurrent upserts of the same `externalId` resolved?
- How does duplicate-in-file detection stay within the 512 MB heap target?
- Why do validation issues not move the job to `FAILED`?
- When and how is the final report published atomically?
- Where is the cancellation safe point?
- What happens when `SIGTERM` arrives during a transaction commit?
- Which heartbeat and status values drive stale recovery?
- Which metrics could accidentally create cardinality explosion?
- Which tests prove that only one worker can claim a job?
- How does the API hide the existence of another user's job?
- Which conditions produce `409`, `410`, `422`, and `503` responses?
