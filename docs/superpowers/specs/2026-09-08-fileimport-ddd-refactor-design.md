# Fileimport DDD Refactor Design

## Status

Approved for planning. The refactor is delivered incrementally in reviewable
commits, with each phase preserving a buildable, tested application.

## Scope And Governing Rules

This design implements `docs/specs/fileimport-ddd-refactor.md` for the complete
`fileimport` lifecycle and creates the required `customer` bounded context.
Business behavior in `AGENTS.md` and `docs/requirement/**` takes precedence
over the refactor spec.

The stale-job conflict is resolved by the master requirement: a stale
`PROCESSING` or `CANCELLATION_REQUESTED` job is finalized as
`FAILED` with `WORKER_LOST`, its running attempt is closed as failed, and the
owner or Admin explicitly requests retry. Recovery does not automatically
requeue it.

The refactor does not add a frontend, a message broker, a workflow engine,
Event Sourcing, CQRS, new runtime frameworks, or any unsupported input format.

## Module Boundaries

`fileimport` owns file metadata, jobs, attempts, CSV validation, reports,
progress, retry, cancellation, recovery, and retention. Its semantic layers
are `api`, `application`, `domain`, and `infrastructure`.

`customer` owns normalized customer state, global `externalId` identity,
atomic import upsert, and `lastImportJobId`. It exposes concrete
`customer.application.CustomerImportService` to the file-import workflow.
`fileimport.application` never accesses `customer.infrastructure`, JPA
entities, Spring Data repositories, or SQL for the customer table.

Technology boundaries use focused capability contracts only where they isolate
a real external or persistence concern: file storage, streamed CSV reading,
error-report storage, duplicate-file coordination, duplicate external-ID
tracking, and search reads. New types do not use `Port`, `Adapter`, `UseCase`,
`RepositoryPort`, or `PersistenceAdapter` naming.

## Domain Model

`ImportFile` is immutable stored-object metadata: owner, display filename,
server-generated storage key, validated SHA-256 checksum, size, detected
content type, bucket/provider, and retention deadline. It contains no status,
counters, error report, or processing behavior.

`ProcessingJob` owns status, progress, counters, current attempt number,
sanitized error state, report key, timestamps, heartbeat, retry and cancellation
decisions. State changes occur only through intent-revealing domain behavior:
claim, record progress, request cancellation, cancel queued, complete, fail,
and request retry. It enforces counters and terminal-state invariants.

`ProcessingAttempt` is append-only child history of `ProcessingJob`, not a
separate aggregate repository. A successful claim creates one `RUNNING`
attempt with an increasing number and an `INITIAL`, `USER_RETRY`,
`ADMIN_RETRY`, or `RECOVERY` trigger. User/Admin retries are limited to three;
operation retries never create another attempt.

## Persistence And Data Migration

New append-only Flyway migrations follow expand, backfill, cutover, contract:

1. Add `processing_job`, `processing_attempt`, indexes and constraints, and
   nullable `customers.last_import_job_id`.
2. Backfill exactly one canonical job per existing `file_import`; map legacy
   incomplete processing to a failed, retryable historical state without
   inventing timestamps.
3. Backfill each customer provenance reference from legacy import file ID to
   its canonical job ID, with row-count verification.
4. Cut application reads and writes to the new ownership model.
5. Remove legacy file-processing columns and `customers.last_import_file_id`
   only after migration and integration checks pass.

`processing_job` has one canonical job per import file, owner/status/creation
indexes for queries and claim ordering, and a heartbeat index for recovery.
`processing_attempt` enforces unique `(job_id, attempt_number)`. Infrastructure
uses atomic Postgres claim/update locking so only one worker can claim a queued
job and create a running attempt.

Customer batch persistence remains a Postgres bulk upsert inside
`customer.infrastructure.persistence`. Each batch transaction returns inserted
and updated counts, preserves commit-order last-write-wins behavior, and never
uses one `save()` call per row.

## Upload And Processing Workflow

The upload API retains the existing route where possible but becomes a
registration operation. It streams one request file to temporary object storage
while calculating SHA-256; checks UTF-8/header; coordinates duplicate detection
by owner/checksum; promotes to a generated canonical key; then persists
`ImportFile` and `ProcessingJob(QUEUED)` in a short transaction. A duplicate or
failed registration deletes the temporary object. Database uniqueness on
`(owner_id, checksum_sha256)` is the final race boundary.

The API returns `202 Accepted` with file and queued-job summary. Customer rows
are not processed during the request.

An infrastructure scheduler invokes an application runner. The runner atomically
claims a queued job, streams the original object, normalizes and validates
complete CSV records, tracks valid duplicate external IDs within the attempt,
and batches only valid rows. Batches default to 1,000 rows and capacity is
bounded by the configurable in-flight limit, default 4. The runner calls
`CustomerImportService` once per logical batch; it never wraps a whole import in
one transaction or materializes the whole file, report, or ID set in heap.

Invalid rows generate every validation issue into a streamed temporary report
while incrementing `invalidRows` once per source row. Only a
`COMPLETED_WITH_ERRORS` job publishes the final report. Failed or cancelled
attempts never publish a final report. Progress and heartbeat persist only after
5,000 completed rows or two seconds; EOF sets total rows and 100 percent.

Queued cancellation atomically becomes `CANCELLED`. Processing cancellation
becomes `CANCELLATION_REQUESTED`; the runner finishes or rolls back the current
batch and stops at the next safe point. Retry retains job and file IDs, resets
current runtime state, preserves prior attempts, queues the job, and creates its
next attempt only when claimed.

## API, Authorization, And Errors

Job-centric endpoints provide list, detail, progress, final-report download,
retry, and cancellation. Operator queries and commands are owner-scoped; a
cross-owner resource consistently returns `404`. Admins can query all jobs and
use owner filters, but cannot bypass retry limits.

File-import API exceptions and response error enums live in
`fileimport.application.exception`, with English and Vietnamese i18n keys.
Domain rules remain HTTP-free. Infrastructure failures are classified to stable,
sanitized system codes; row validation remains a successful business processing
outcome. Responses, logs, audit metadata, and metrics never expose stack traces,
credentials, storage keys, whole customer rows, or unmasked PII.

## Reliability And Operations

Scheduler and cleanup entry points live in `fileimport.infrastructure` and call
application services. They implement atomic stale-job recovery, temporary
object cleanup, retention-compatible original/report cleanup, and cooperative
shutdown. External I/O stays outside long database transactions. Transient
operations have bounded retry, timeout, and cancellation/close behavior.

Audit, structured logs, and metrics cover upload, duplicate detection, job
claim/finalization, cancellation, retry, failure, and stale recovery without
high-cardinality identifiers as metric labels.

## Test Strategy

Every phase adds or migrates tests with the code it protects:

- Domain tests cover `ImportFile` invariants and every legal/illegal
  `ProcessingJob` transition, counters, retries, attempts, and cancellation.
- Application tests cover async upload registration, cleanup, processing,
  report publication, cadence, partial commits, retry, and safe cancellation.
- PostgreSQL/Testcontainers tests cover duplicate upload uniqueness, job claim,
  attempt uniqueness, tracker lifecycle, customer batch upsert, stale recovery,
  and Flyway/backfill correctness.
- API tests cover `202` upload, ownership privacy, Admin rules, pagination,
  report availability, retry, and cancellation semantics.
- Streaming/resource tests use generated data and prove bounded behavior without
  committing large fixtures.

The final gate runs `./mvnw spotless:apply`, `./mvnw spotless:check`, and
`./mvnw verify`.

## Delivery Phases

1. Characterize existing behavior and add architecture guard tests.
2. Add target file-import domain model and expand migrations.
3. Create customer context and migrate bulk upsert ownership.
4. Migrate file-import persistence, storage, CSV, configuration, and errors to
   target layers.
5. Cut upload over to registration plus queued job and `202` response.
6. Implement atomic claim and streaming `ProcessingJobRunner`.
7. Complete progress, error-report, batch-result, and bounded-resource flow.
8. Add job queries, retry, cancellation, recovery, cleanup, audit, and metrics.
9. Backfill job/customer provenance and verify migration data.
10. Remove legacy packages/types/columns and run architecture scans plus the
    final verification gate.

Each coherent phase is committed only after its focused formatting and tests
pass; no phase keeps two independent processing implementations or state
machines.
