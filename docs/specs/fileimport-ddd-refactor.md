# SPEC — Refactor `fileimport` sang Pragmatic Modular DDD và đưa business lifecycle về đúng requirement

## 1. Status

**Decision:** APPROVED DIRECTION — Recommendation B.

Refactor này **không phải package rename**. Mục tiêu là đồng thời:

1. migrate `fileimport` khỏi legacy Hexagonal sang **Pragmatic Modular DDD** theo `RULE.md` / `ARCHITECTURE.md`;
2. sửa lại domain boundary để đúng business model trong `AGENTS.md` và `docs/requirement/**`;
3. tách `ImportFile`, `ProcessingJob`, `ProcessingAttempt` đúng ownership;
4. chuyển upload từ xử lý đồng bộ sang **register + enqueue async job**;
5. tách Customer persistence ra khỏi `fileimport` thành `customer` bounded context;
6. giữ pipeline streaming, bounded memory, batch transaction, retry/cancel/recovery và error report đúng requirement.

Spec này là source of truth cho refactor production code của `fileimport` và phần `customer` bắt buộc phải tách ra để bảo vệ module boundary.

---

## 2. Source of truth và precedence

Agent MUST đọc theo thứ tự trước khi sửa code:

1. `AGENTS.md` — business behavior và invariant.
2. `docs/requirement/02-domain-model-and-business-rules.md`.
3. `docs/requirement/03-functional-specification.md`.
4. `docs/requirement/04-non-functional-requirements.md`.
5. `ARCHITECTURE.md` — module/layer/dependency contract.
6. `RULE.md` — engineering contract.
7. `LIBRARY.md` — shared APIs phải reuse.
8. Spec này — design/refactor scope đã chốt.
9. Existing source/tests — evidence của implementation hiện tại.

Nếu spec này mâu thuẫn business requirement thì **business requirement thắng**.

Agent MUST NOT sửa requirement để giữ implementation cũ.

---

## 3. Current-state problems phải được loại bỏ

Current `fileimport` đang có các vấn đề kiến trúc/business sau:

### 3.1 Legacy Hexagonal structure

Hiện còn:

```text
fileimport/adapter/in/**
fileimport/adapter/out/**
fileimport/application/port/in/**
fileimport/application/port/out/**
*UseCase
*RepositoryPort
*PersistenceAdapter
```

Đây là legacy implementation. Target architecture không được giữ hoặc mở rộng các pattern này.

### 3.2 Sai aggregate boundary

Current `FileImport` đang giữ cả:

```text
file metadata
processing status
processedRows
validRows
invalidRows
insertedRows
updatedRows
errorReportKey
complete()
fail()
```

Business model yêu cầu:

```text
ImportFile
  -> immutable stored-file metadata

ProcessingJob
  -> state machine
  -> progress
  -> counters
  -> retry/cancel
  -> heartbeat
  -> final result

ProcessingAttempt
  -> history của từng execution
```

Processing state MUST được loại khỏi `ImportFile`.

### 3.3 Upload đang xử lý toàn file trong request

Current flow gần như:

```text
HTTP upload
 -> store
 -> validate
 -> parse whole stream
 -> customer upsert
 -> report
 -> complete
 -> response
```

Target flow bắt buộc:

```text
HTTP upload
 -> stream store + checksum
 -> validate file/header
 -> duplicate control
 -> register ImportFile + QUEUED ProcessingJob
 -> 202 Accepted

background worker
 -> claim job
 -> stream file
 -> validate + batch customer upsert
 -> progress/report
 -> terminal state
```

Upload endpoint MUST NOT chờ row processing.

### 3.4 `fileimport` đang sở hữu Customer persistence

Current dependency:

```text
fileimport.application
 -> fileimport.adapter.out.persistence.CustomerUpsertRepository
 -> JdbcTemplate
 -> customers table
```

Target:

```text
fileimport.application
 -> customer.application.CustomerImportService
 -> customer.infrastructure.persistence
```

`fileimport` MUST NOT import hoặc query trực tiếp:

```text
customer.infrastructure/**
Customer JPA entity
Customer Spring Data repository
JdbcTemplate SQL ghi bảng customers
```

---

## 4. Scope

### 4.1 In scope

Refactor phải đưa implementation tới đầy đủ business lifecycle sau:

1. Upload và register một customer CSV.
2. Duplicate file detection theo `(ownerId, checksumSha256)`.
3. Tạo canonical `ProcessingJob` ở trạng thái `QUEUED`.
4. Async worker claim job an toàn.
5. Stream parse CSV.
6. Normalize + validate row.
7. Detect duplicate `external_id` trong cùng file.
8. Bounded logical batch processing.
9. Cross-context Customer batch upsert.
10. Progress + heartbeat.
11. Error report streaming/publishing.
12. Job detail/list/progress.
13. Retry.
14. Cooperative cancellation.
15. Crash/stale-job recovery.
16. Retention-compatible cleanup.
17. Ownership/Admin authorization.
18. Audit + operational logging/metrics theo rule hiện tại.
19. Migration dữ liệu/schema hiện tại sang domain model mới.
20. Xóa legacy Hexagonal code sau khi caller đã migrate.

### 4.2 Out of scope

Không thêm:

- frontend;
- Excel/JSON/XML/PDF/OCR import;
- multi-file upload;
- Kafka/RabbitMQ/workflow engine chỉ để chạy async;
- Event Sourcing;
- CQRS framework;
- exact byte-offset resume;
- auto ingestion từ email/SFTP/cloud drive;
- automatic email notification;
- rewrite module `auth` ngoài dependency cần thiết;
- framework/runtime dependency mới nếu requirement hiện tại không bắt buộc.

Async processing SHOULD dùng persisted DB job + scheduler/worker trong modular monolith. Không thêm broker chỉ để trông giống distributed architecture.

---

## 5. Capability map

Refactor ảnh hưởng hai bounded context, nhưng ownership phải rõ:

| Module | Ownership | Depends on |
|---|---|---|
| `fileimport` | file lifecycle, processing job, attempt, CSV import validation, progress, report, retry/cancel/recovery | `customer.application` |
| `customer` | Customer business identity, normalized persisted customer state, atomic import upsert | — |

Dependency direction:

```text
fileimport.application
        |
        v
customer.application
        |
        v
customer.domain
        ^
        |
customer.infrastructure
```

Forbidden:

```text
fileimport -> customer.infrastructure
fileimport -> customer.persistence entity
customer -> fileimport.infrastructure
```

`customer` MAY receive `sourceJobId` as an external aggregate identity. Nó không được load `ProcessingJob` từ `fileimport` persistence.

---

## 6. Target architecture

### 6.1 `fileimport`

Target semantic structure:

```text
fileimport/
├── api/
│   ├── FileImportController
│   ├── ProcessingJobController
│   ├── dto/request/
│   ├── dto/response/
│   └── mapper/
│
├── application/
│   ├── FileImportProperties
│   ├── service/
│   │   ├── FileImportCommandService
│   │   ├── ProcessingJobCommandService
│   │   ├── ProcessingJobQueryService
│   │   └── ProcessingJobRunner
│   ├── capability/
│   │   ├── FileStorage
│   │   ├── CustomerCsvReader
│   │   ├── ErrorReportStore
│   │   ├── DuplicateExternalIdTracker
│   │   ├── DuplicateFileCoordinator
│   │   └── ProcessingJobSearchRepository
│   ├── command/
│   ├── query/
│   ├── result/
│   └── exception/
│
├── domain/
│   ├── model/
│   │   ├── ImportFile
│   │   ├── FileChecksum
│   │   ├── ProcessingJob
│   │   ├── ProcessingAttempt
│   │   ├── JobStatus
│   │   ├── AttemptStatus
│   │   ├── AttemptTrigger
│   │   ├── JobProgress
│   │   └── ValidationIssue
│   ├── ImportFileRepository
│   ├── ProcessingJobRepository
│   ├── policy/
│   └── exception/
│
└── infrastructure/
    ├── persistence/
    ├── storage/
    ├── csv/
    ├── coordination/
    ├── scheduling/
    └── config/
```

Đây là semantic target, không phải bắt buộc tạo mọi package/type nếu implementation nhỏ hơn vẫn bảo vệ boundary.

MUST NOT tạo empty package hoặc interface chỉ để khớp tree.

### 6.2 `customer`

Current repo chưa có top-level `customer` Java module. Refactor này MUST tạo module đó vì current Customer persistence đang nằm sai bounded context.

Target tối thiểu:

```text
customer/
├── application/
│   ├── service/
│   │   └── CustomerImportService
│   ├── command/
│   ├── result/
│   └── capability/
│       └── CustomerBatchWriter
│
├── domain/
│   └── model/
│       └── Customer
│
└── infrastructure/
    └── persistence/
        └── PostgresCustomerBatchWriter
```

Không tạo Customer REST API nếu requirement hiện tại không cần.

High-volume batch upsert là một specialized application capability hợp lệ. Agent MUST NOT đổi thành `repository.save()` từng row chỉ để architecture nhìn thuần DDD.

---

## 7. Dependency rules

### 7.1 Allowed

```text
fileimport.api            -> fileimport.application
fileimport.application    -> fileimport.domain
fileimport.infrastructure -> fileimport.application/domain

fileimport.application    -> customer.application
customer.application      -> customer.domain
customer.infrastructure   -> customer.application/domain
```

### 7.2 Forbidden

```text
domain -> application/api/infrastructure
application -> api
application -> Spring Data/JPA entity/JdbcTemplate/storage SDK/Redis SDK
module A -> module B.infrastructure
module A -> module B persistence model
```

### 7.3 Interface rule

Concrete application service là default.

MUST delete inbound ceremony như:

```text
UploadFileUseCase
```

Interfaces chỉ tồn tại khi có boundary thật, ví dụ:

- object storage;
- CSV parser implementation;
- streamed report output;
- Redis/distributed coordination;
- duplicate-ID tracking persistence;
- high-volume database writer;
- domain aggregate repository.

Không tạo `XxxPort`, `XxxAdapter`, `XxxUseCaseImpl`.

---

## 8. Domain model — `ImportFile`

### 8.1 Aggregate root

Rename domain concept từ `FileImport` thành **`ImportFile`** để khớp ubiquitous language trong requirement.

`ImportFile` đại diện immutable metadata của original object đã store thành công.

Fields logic:

```text
id
ownerId
originalFilename
storageKey
checksumSha256 / FileChecksum
sizeBytes
detectedContentType
retentionDeadline
bucket
storageProvider
createdAt
version nếu persistence cần
```

### 8.2 Invariants

MUST:

- storage key do server generate;
- không derive storage path trực tiếp từ filename user;
- checksum phải lowercase SHA-256 64 hex chars;
- size không âm;
- metadata chỉ được commit sau khi object store thành công;
- logical uniqueness `(ownerId, checksumSha256)`;
- content của registered ImportFile không bị mutate;
- không chứa processing state/counters/report state.

MUST NOT còn các field/method sau trên `ImportFile`:

```text
processingStatus
processedRows
validRows
invalidRows
insertedRows
updatedRows
errorReportKey
complete(...)
fail()
```

### 8.3 Repository

`domain/ImportFileRepository` là aggregate repository.

Nó nói bằng domain language, không expose Spring Data types.

Các operation tối thiểu theo business có thể gồm:

```text
save aggregate
find by id + visibility/owner requirement
find canonical duplicate by owner + checksum
```

Không dùng tên `FileImportRepositoryPort`.

---

## 9. Domain model — `ProcessingJob`

### 9.1 Aggregate root

`ProcessingJob` sở hữu toàn bộ processing lifecycle.

Logical fields:

```text
id
importFileId
ownerId
status
processedRows
validRows
invalidRows
insertedRows
updatedRows
totalRows nullable
currentAttempt
errorCode nullable
errorSummary nullable
errorReportKey nullable
startedAt nullable
finishedAt nullable
heartbeatAt nullable
version
createdAt
updatedAt
```

Nếu `cancelRequested` đã được biểu diễn đầy đủ bằng `CANCELLATION_REQUESTED`, không tạo duplicate boolean state trừ khi persistence/concurrency requirement chứng minh cần.

### 9.2 Job statuses

```text
QUEUED
PROCESSING
CANCELLATION_REQUESTED
COMPLETED
COMPLETED_WITH_ERRORS
FAILED
CANCELLED
```

### 9.3 Allowed transitions

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
FAILED -> QUEUED           approved retry/recovery
CANCELLED -> QUEUED        approved retry
```

MUST reject:

```text
COMPLETED -> retry
COMPLETED_WITH_ERRORS -> retry
FAILED -> PROCESSING directly
PROCESSING -> QUEUED without ending active attempt
cancel COMPLETED
cancel COMPLETED_WITH_ERRORS
cancel FAILED
multiple workers claiming the same QUEUED job
```

### 9.4 Counters invariants

At all persisted checkpoints:

```text
processedRows = validRows + invalidRows
insertedRows + updatedRows <= validRows
all counters >= 0
```

Terminal rules:

```text
COMPLETED             -> invalidRows == 0
COMPLETED_WITH_ERRORS -> invalidRows > 0
FAILED                -> system failure only
CANCELLED             -> worker stopped at safe point,
                         except QUEUED cancellation before claim
```

Progress MUST NOT decrease inside one attempt.

### 9.5 Domain behavior

Status changes MUST đi qua behavior có intent như:

```text
claim(...)
recordProgress(...)
requestCancellation(...)
cancelQueued(...)
complete(...)
fail(...)
requestRetry(...)
```

Không được public setter arbitrary status/counters.

Time-dependent behavior nhận `Instant` từ application; application dùng injected `Clock`.

---

## 10. `ProcessingAttempt`

`ProcessingAttempt` là entity/history thuộc lifecycle của `ProcessingJob`.

Logical fields:

```text
id
jobId
attemptNumber
trigger
status
startedAt
finishedAt
processedRows
validRows
invalidRows
insertedRows
updatedRows
errorCode
errorSummary
```

Triggers:

```text
INITIAL
USER_RETRY
ADMIN_RETRY
RECOVERY
```

Statuses:

```text
RUNNING
SUCCEEDED
FAILED
CANCELLED
```

Rules:

- attempt number tăng tuần tự từ 1;
- tối đa một RUNNING attempt cho một job;
- history append-only về mặt business;
- retry không overwrite attempt cũ;
- operation-level retry không tạo attempt mới;
- recovery run tạo attempt mới trigger `RECOVERY`;
- tối đa 3 user/admin retry actions; initial attempt không tính vào limit này;
- recovery attempt không tính vào user/admin retry limit.

Không tạo `ProcessingAttemptRepository` riêng nếu attempt được quản lý trong consistency boundary của ProcessingJob.

---

## 11. Customer bounded context

### 11.1 Ownership

`customer` owns:

```text
Customer
externalId business identity
normalized persisted fields
last import job responsible for state
atomic upsert semantics
```

### 11.2 Customer aggregate business rules

Fields:

```text
id
externalId
fullName
email
phone
dateOfBirth
address
lastImportJobId
createdAt
updatedAt
version when needed
```

Rules:

- `externalId` globally unique;
- first occurrence inserts;
- existing occurrence updates all imported snapshot fields;
- existing record counts as `updated` even if values are identical;
- empty imported address overwrites old address as `null`;
- internal customer ID never changes on update;
- later imports do not delete customers omitted from the file;
- `lastImportJobId` references the ProcessingJob responsible for latest persisted state;
- concurrent jobs use DB commit-order last-write-wins;
- use atomic database upsert/equivalent transaction strategy.

### 11.3 Cross-module contract

`fileimport.application` calls concrete:

```text
customer.application.CustomerImportService
```

Customer module owns its input/result contract.

Input của batch MUST contain only normalized valid data + `sourceJobId` cần thiết cho Customer persistence.

Return MUST expose at least:

```text
insertedRows
updatedRows
```

MUST NOT expose JdbcTemplate/JPA entities/SQL details ra `fileimport`.

### 11.4 Performance boundary

One transaction per logical customer batch.

MUST NOT:

```text
loop 1000 rows -> 1000 repository.save()
```

Target implementation must preserve database batch/atomic upsert performance. PostgreSQL-specific SQL stays inside `customer.infrastructure.persistence`.

---

## 12. CSV validation ownership

### 12.1 Technical parsing

CSV syntax/parser implementation belongs to:

```text
fileimport.infrastructure.csv
```

Current `adapter/in/csv/**` MUST migrate there or be replaced.

Application MUST NOT import infrastructure classes directly. Nếu orchestration cần streaming parser, expose một focused application capability such as `CustomerCsvReader` implemented by infrastructure.

### 12.2 Business validation

Business rules của import row MUST not remain arbitrary infrastructure validation.

Validation policy must preserve:

```text
external_id
full_name
email
phone
date_of_birth
address
```

exact normalization/validation rules from `AGENTS.md` / requirement docs.

A row can have multiple issues. Validator MUST return all issues for that row instead of fail-fast on first field.

`invalidRows` tăng một lần cho original row, trong khi error report có một record cho mỗi issue.

### 12.3 Duplicate external IDs inside one file

Rule:

```text
first valid occurrence -> process
later occurrence       -> DUPLICATE_EXTERNAL_ID_IN_FILE
```

Tracking MUST be scoped per job/attempt and must remain inside memory target.

MUST NOT assume a 1,000,000-entry in-memory `HashSet` is acceptable.

Existing PostgreSQL-backed strategy may be reused/refactored if tests prove correct lifecycle and cleanup.

Technical capability should be named by meaning, e.g. `DuplicateExternalIdTracker`, not `DuplicateExternalIdTrackerPort`.

---

## 13. Upload workflow

### 13.1 API behavior

Upload accepts exactly one CSV.

Success response MUST be **`202 Accepted`**, because processing continues asynchronously.

Upload result must contain enough data for client to monitor job, minimum:

```text
fileId
jobId
jobStatus = QUEUED
originalFilename
sizeBytes
uploadedAt/createdAt
```

### 13.2 Required sequence

```text
1. authenticate caller
2. enforce upload permission
3. validate exactly one file
4. reject empty/oversize/invalid filename/unsupported content
5. stream to temporary storage key while computing SHA-256
6. validate UTF-8 + CSV header using stored stream/prefix
7. coordinate duplicate check by ownerId + checksum
8. DB unique constraint remains final duplicate correctness boundary
9. promote/copy temporary object to canonical server-generated key
10. short DB transaction:
      create ImportFile
      create canonical ProcessingJob(QUEUED)
11. audit accepted upload
12. return 202
```

Object storage operations MUST stay outside DB transaction.

### 13.3 Duplicate behavior

Same owner + same checksum:

```text
no new ImportFile
no new ProcessingJob
delete losing temporary object
return 409 duplicate
```

Conflict response/error context must make existing canonical resource discoverable according to existing response contract, including where supported:

```text
existing fileId
existing jobId
jobStatus
upload time
```

Duplicate rule still applies when existing job is `FAILED` or `CANCELLED`; user must retry the existing job.

Different owners MAY upload identical bytes and receive separate files/jobs.

Redis/Redisson coordination is an optimization/coordination boundary only. Database unique constraint is correctness boundary.

Losing a DB uniqueness race MUST become clean `409`, never generic `500`.

---

## 14. Async worker architecture

### 14.1 Entry point

Non-HTTP worker entry point belongs to:

```text
fileimport.infrastructure.scheduling
```

Scheduler MUST call application service. Business workflow MUST NOT live inside scheduler.

Recommended responsibility split:

```text
ProcessingJobScheduler
    -> asks application to claim/run available work

ProcessingJobCommandService
    -> short transactional state transitions

ProcessingJobRunner
    -> long-running orchestration WITHOUT one giant DB transaction
```

### 14.2 Claim

Claim must be atomic.

Two workers/processes MUST NOT both move the same job from `QUEUED` to `PROCESSING`.

PostgreSQL-specific locking/claim SQL belongs in infrastructure persistence. `FOR UPDATE SKIP LOCKED`, conditional update, optimistic locking, or equivalent is acceptable if concurrency test proves one winner.

Claim order:

```text
createdAt ASC
```

On successful claim:

```text
job -> PROCESSING
create attempt RUNNING
attemptNumber increments
trigger resolved
startedAt set
heartbeatAt set
```

### 14.3 Long-running transaction rule

MUST NOT wrap full file processing in one transaction.

Required logical transactions:

```text
TX-A: claim job + create RUNNING attempt

for each logical batch:
  TX-B: customer atomic upsert for batch
  TX-C: bounded progress update when cadence requires

TX-D: finalize job + attempt
```

Progress update MUST NOT roll back already committed customer batches.

External storage I/O MUST NOT occur inside long DB transaction.

---

## 15. Processing pipeline

Conceptual flow:

```text
stored original file
      |
      v
atomic claim
      |
      v
open streaming source
      |
      v
CSV record parse
      |
      v
normalize + collect validation issues
      |
      +--> invalid -> streamed report issue(s)
      |
      `--> valid
             |
             v
        logical batch
             |
             v
 customer.application.CustomerImportService
             |
             v
 atomic customer upsert
             |
             v
 progress/heartbeat
      |
      v
safe-point cancellation check
      |
      v
EOF -> finalize report/job/attempt
```

### 15.1 Bounded resource usage

Defaults:

```text
logical batch size = 1,000 rows
max in-flight batches = 4
```

Both configurable under `app.fileimport.*`.

MUST NOT create:

- unbounded executor;
- unbounded queue;
- unbounded list of parsed rows;
- one future per row/file record;
- `ForkJoinPool.commonPool()` pipeline;
- whole-file byte array/string.

When capacity is full, producer/parser must block/apply backpressure.

### 15.2 Threading

Follow NFR:

- CPU-bound validation/transformation: bounded platform-thread executor;
- blocking I/O may use virtual threads only when concurrency is still explicitly bounded;
- all executor sizing must be configurable or derived from verified limits;
- timeout alone is not cancellation: close/interrupt underlying operation when supported.

Do not introduce concurrency if sequential batch processing already meets NFR. Simpler bounded design wins unless benchmark justifies parallelism.

---

## 16. Progress and heartbeat

Do not persist progress per row.

Update when either:

```text
>= 5,000 newly completed rows
OR
>= 2 seconds since previous progress update
```

During streaming:

```text
totalRows       may be null
progressPercent may be null
processedRows   always available
heartbeatAt     always current within allowed cadence
```

At EOF:

```text
totalRows = final data row count
progressPercent = 100
```

Terminal response counters MUST be exact.

Read endpoints may be eventually consistent up to two seconds.

---

## 17. Error report

Final report exists only for:

```text
COMPLETED_WITH_ERRORS
```

No final downloadable report for:

```text
COMPLETED
FAILED
CANCELLED
PROCESSING
QUEUED
```

Required columns:

```csv
row_number,external_id,error_code,field,error_message,original_data
```

Requirements:

- UTF-8 with BOM;
- physical row order ascending;
- all issues of same original row adjacent;
- safe CSV escaping;
- `original_data` serialized safely;
- streamed generation, not in-memory accumulation;
- protected object/private bucket;
- owner/Admin authorization;
- failed/cancelled attempt temporary report is not published as final business report.

`COMPLETED` -> `409 REPORT_NOT_AVAILABLE`.

Expired final report -> `410 REPORT_EXPIRED`.

Download may stream authenticated response or use signed URL <= 5 minutes according to existing product decision.

---

## 18. Cancellation

Cancellation is cooperative.

### QUEUED

Atomic:

```text
QUEUED -> CANCELLED
```

Worker must not claim after transition.

### PROCESSING

API/application:

```text
PROCESSING -> CANCELLATION_REQUESTED
return 202
```

Worker:

```text
stop accepting new batches
finish/rollback current batch normally
reach safe point between batches
discard unpublished temporary report
attempt -> CANCELLED
job -> CANCELLED
```

Already committed customer batches remain committed.

Request cancellation is idempotent for:

```text
CANCELLATION_REQUESTED
CANCELLED
```

Successful terminal job -> `409 JOB_NOT_CANCELLABLE`.

No unsafe thread kill APIs.

---

## 19. Retry

Retry allowed only when:

```text
status in {FAILED, CANCELLED}
original file still exists
retention not expired
no attempt RUNNING
user/admin retry limit not exceeded
```

Retry behavior:

```text
keep same jobId
keep same importFileId
preserve old attempts
reset current progress/runtime fields
job -> QUEUED
new attempt is NOT created yet
new attempt created when worker claims
return 202
```

Retry starts reading original file from beginning.

Previously committed customer batches remain. Atomic upsert makes replay idempotent regarding duplicate Customer creation; new attempt counters start from zero.

Successful terminal states are not retryable.

Expired original -> `410 ORIGINAL_FILE_EXPIRED`.

---

## 20. Operation-level retry

Infrastructure transient operations may retry at most three times with approximately:

```text
1s
2s
4s
+ jitter
```

Apply only to classified transient failures.

Do not retry:

- row validation failure;
- authorization failure;
- invalid CSV structure;
- permanent uniqueness/business conflict;
- unsupported file type.

Default operation timeout:

```text
storage read: 30s
database batch: 30s
report write: 30s
```

Make operational timeout configurable.

Operation retry MUST NOT increment ProcessingAttempt number.

---

## 21. Recovery

System MUST recover jobs that cannot remain `PROCESSING` forever after crash/restart.

Recovery must use persisted heartbeat and job state.

A stale job in:

```text
PROCESSING
CANCELLATION_REQUESTED
```

must be handled by an internal recovery workflow that:

1. atomically verifies staleness;
2. closes previous RUNNING attempt as failed/interrupted with sanitized system reason;
3. preserves already committed batches;
4. moves job through an allowed recovery path back to `QUEUED` when retry is permitted;
5. next worker claim creates a new attempt with trigger `RECOVERY`;
6. restarts processing from beginning;
7. never creates two simultaneous RUNNING attempts.

Recovery MUST be idempotent when multiple scheduler instances inspect the same stale job.

Do not fabricate precise historical timestamps that do not exist; preserve best available data.

---

## 22. Query APIs

Exact URI can follow current API prefix/version convention, but capabilities are mandatory.

Recommended job-centric shape:

```text
POST /file-import
GET  /file-import/jobs
GET  /file-import/jobs/{jobId}
GET  /file-import/jobs/{jobId}/progress
GET  /file-import/jobs/{jobId}/error-report
POST /file-import/jobs/{jobId}/retry
POST /file-import/jobs/{jobId}/cancel
```

Admin audit endpoint may follow existing audit conventions.

Current upload URI SHOULD be preserved where possible to minimize unrelated client breakage.

Current file-id error-report endpoint may temporarily delegate to the new job-centric application query during migration if callers exist. It MUST NOT remain as a second implementation of report logic.

### 22.1 Job list

Filters:

```text
status
original filename keyword
created time range
owner filter for Admin only
```

Defaults:

```text
sort createdAt DESC
page size 20
max page size 100
```

Use common paging contract required by `RULE.md`; search/read model belongs behind `application/capability/ProcessingJobSearchRepository`, not domain repository.

### 22.2 Job detail

Must include:

```text
file metadata
owner summary/identity allowed by contract
job status
counters
progress
current attempt
attempt history
timestamps
report availability
sanitized error summary
Admin-only technical code if required
computed available actions
```

### 22.3 Progress response

Must include:

```text
status
processedRows
validRows
invalidRows
insertedRows
updatedRows
totalRows nullable
progressPercent nullable
startedAt
heartbeatAt
```

---

## 23. Authorization

Backend enforcement is mandatory.

### Operator

Can act only on resources they own.

For resource owned by another user:

```text
404
```

not 403, to avoid resource enumeration.

Operator may:

- upload;
- list own jobs;
- read own detail/progress;
- download own final report;
- retry/cancel own eligible jobs.

### Admin

Can:

- list all jobs;
- filter by owner;
- inspect sanitized technical failure metadata;
- retry/cancel eligible jobs;
- view required audit history.

Admin cannot bypass retry limit.

Authorization MUST be applied in application/data-access boundary as needed, not only controller/UI.

`fileimport` MUST NOT read `auth.infrastructure` repositories/entities to authorize.

---

## 24. Persistence target

### 24.1 Existing `file_import` table

Keep table as ImportFile metadata storage for pragmatic migration compatibility.

After refactor it must contain file metadata only.

Processing fields currently added to `file_import` are legacy and MUST be removed only after backfill and code cutover:

```text
processing_status
processed_rows
valid_rows
invalid_rows
inserted_rows
updated_rows
error_report_key
```

Do not edit old Flyway migrations. Fix forward using new append-only migrations.

### 24.2 New `processing_job`

Must persist at least:

```text
id PK
import_file_id
owner_id
status
processed_rows
valid_rows
invalid_rows
inserted_rows
updated_rows
total_rows nullable
current_attempt
error_code nullable
error_summary nullable
error_report_key nullable
started_at nullable
finished_at nullable
heartbeat_at nullable
version
created_at
last_modified_at
soft-delete/audit columns required by RULE/common base
```

Constraints/indexes must support:

- one canonical processing job per ImportFile in first release;
- owner job list;
- deterministic queued claim;
- stale heartbeat scan;
- optimistic/atomic state transition.

Recommended query-driven indexes:

```text
(status, created_at)
(owner_id, created_at DESC)
(status, heartbeat_at)
(import_file_id unique/canonical)
```

Final indexes must be validated against actual SQL/explain, not blindly copied.

### 24.3 New `processing_attempt`

Must persist:

```text
id PK
job_id
attempt_number
trigger
status
started_at
finished_at
counters
error_code
error_summary
created_at
soft-delete/audit fields required by project rule where applicable
```

Constraints:

```text
UNIQUE(job_id, attempt_number)
```

Database/concurrency design must enforce at most one RUNNING attempt per job, through partial uniqueness, locked job transition, or equivalent proven strategy.

### 24.4 Customer table

Current table stores `last_import_file_id`.

Target business field is:

```text
last_import_job_id
```

Migration must preserve existing customer data and map each old `last_import_file_id` to the canonical backfilled/new ProcessingJob for that file before old reference is removed.

Do not silently null historical source provenance.

---

## 25. Schema/data migration strategy

Use **expand -> backfill -> cutover -> contract**.

### Stage A — expand

Add new schema without dropping legacy processing data:

```text
processing_job
processing_attempt
customer.last_import_job_id (initially compatible with backfill)
required indexes/constraints
missing soft-delete/audit columns when RULE/common entity requires them
```

### Stage B — backfill legacy files

For every existing `file_import` row create exactly one canonical ProcessingJob.

Map legacy status:

```text
COMPLETED             -> COMPLETED
COMPLETED_WITH_ERRORS -> COMPLETED_WITH_ERRORS
FAILED                -> FAILED
PROCESSING             -> FAILED/recoverable legacy-incomplete state
```

Do NOT backfill legacy `PROCESSING` as currently-running worker state because no worker owns it after deployment cutover.

Backfill attempt history using the best available historical information. Do not invent exact start/finish times where not available.

### Stage C — backfill customer provenance

For every customer:

```text
old last_import_file_id
 -> canonical processing_job.import_file_id
 -> processing_job.id
 -> last_import_job_id
```

Verify row counts before contract step.

### Stage D — application cutover

New code reads/writes:

```text
ImportFile metadata -> file_import
processing lifecycle -> processing_job / processing_attempt
customer source      -> last_import_job_id
```

### Stage E — contract

Only after verification:

- remove legacy processing columns from `file_import`;
- remove old `customers.last_import_file_id`;
- remove legacy Java types/packages.

Migrations are append-only. Never modify historical Flyway files already merged/applied.

---

## 26. Persistence model decision

Do not mechanically duplicate domain/entity/mapper for every aggregate.

For each aggregate agent MUST explicitly apply `RULE.md §6.4`:

### `ImportFile`

Existing schema and migration history differ from final domain shape. A separate persistence entity + mapper is justified during/after migration if it keeps legacy schema concerns out of immutable domain model.

### `ProcessingJob`

If JPA annotations do not distort its state-machine behavior, direct JPA mapping MAY be used. Separate entity is acceptable when query/locking/persistence concerns materially differ.

### `ProcessingAttempt`

Treat as child/history persistence of ProcessingJob lifecycle. Do not create repository ceremony for it.

### Customer batch writer

PostgreSQL-specific bulk upsert can remain JDBC/native SQL in `customer.infrastructure.persistence`. It does not need to be rewritten to per-row JPA.

---

## 27. Mapping current code to target

This table is migration guidance, not permission to mechanically rename classes.

| Current | Target direction |
|---|---|
| `adapter/in/web/FileImportController` | `api/FileImportController` |
| `adapter/in/csv/**` | `infrastructure/csv/**` behind application capability where needed |
| `adapter/out/storage/r2/R2ObjectStorageAdapter` | `infrastructure/storage/R2FileStorage` |
| `application/port/out/ObjectStoragePort` | `application/capability/FileStorage` |
| `application/port/out/DuplicateExternalIdTracker` | `application/capability/DuplicateExternalIdTracker` |
| `application/port/out/FileImportRepositoryPort` | `domain/ImportFileRepository` |
| `application/port/in/UploadFileUseCase` | DELETE; controller calls concrete application service |
| `adapter/out/persistence/FileImportPersistenceAdapter` | `infrastructure/persistence/JpaImportFileRepository` or equivalent |
| `adapter/out/persistence/CustomerUpsertRepository` | MOVE responsibility to `customer.infrastructure.persistence` |
| `CustomerUpsertResult` | `customer.application.result` contract |
| `UploadFileService` | decompose into `FileImportCommandService`; no row processing |
| `CustomerImportProcessor` | evolve/decompose into `ProcessingJobRunner` workflow |
| `CsvErrorReportWriter` | `infrastructure/csv` / report implementation behind application boundary |
| domain `FileImport` | rename/rebuild as immutable `ImportFile` |
| domain `ImportProcessingStatus` | replace with `JobStatus` owned by ProcessingJob |
| domain `FileImportErrorCode` | move application error catalog to `application/exception` |
| `fileimport/configuration/**` | split typed properties vs technical infrastructure config according to RULE |

Legacy type removal happens only after all callers/tests migrate.

---

## 28. Error model and i18n

Current `FileImportErrorCode` is in domain and implements HTTP response contract. Target MUST fix layer ownership.

### Domain

Pure rules only:

```text
<Module>Rule
<Module>RuleViolation
```

No HTTP status, numeric error code, i18n key.

### Application

Owns response errors/exceptions, for example categories:

```text
FILE_IMPORT_*
PROCESSING_JOB_*
```

Names MUST have module/business prefix and matching keys in:

```text
messages.properties
messages_vi.properties
```

Agent MUST inventory existing numeric codes before change.

- If a code is already externally published: do not silently renumber; record compatibility decision.
- If current code is unpublished/local-only and violates repository numbering rule, normalize using repository-wide `{httpStatus}{sequence}` allocation.

Required error semantics include at least:

```text
file required
only one file allowed
empty file
file too large
unsupported type
invalid CSV/header
malformed CSV
duplicate file
storage unavailable
job not found
job not cancellable
job not retryable
retry limit exceeded
original file expired
report not available
report expired
processing conflict / optimistic conflict
```

Do not leak stack traces, raw infrastructure errors, full customer PII, object credentials, or storage keys not intended for clients.

---

## 29. Configuration

Use typed `@ConfigurationProperties`, validated, namespace:

```text
app.fileimport.*
```

Operational/business settings that application needs may be grouped in `FileImportProperties` accessible without importing infrastructure.

At minimum configurable:

```text
max file size (default 500 MB)
retention
logical batch size (default 1000)
max in-flight batches (default 4)
progress row interval (default 5000)
progress time interval (default 2s)
operation timeout (default 30s)
claim/poll cadence
stale heartbeat threshold
worker concurrency
retry/backoff parameters where project convention permits
```

R2/S3 credentials, Redis endpoints and technical client config remain infrastructure/configuration concerns with no insecure defaults.

Do not scatter `@Value`.

---

## 30. Logging, audit and observability

Follow `RULE.md` logging contract.

### MUST log operational lifecycle, without PII

Useful events include:

```text
upload accepted
upload duplicate race
job claimed
attempt started
job completed
job completed with errors
job failed
cancel requested
job cancelled
retry requested
stale recovery
storage/db/report transient retry
```

Do not log:

- whole CSV rows;
- full email/phone;
- original file content;
- auth tokens;
- storage credentials;
- report body.

Use IDs (`fileId`, `jobId`, `attemptId`) for correlation.

Metrics SHOULD expose at least:

```text
queued/running/terminal job counts
job duration
rows processed
batch duration
claim conflict
storage failure
database batch failure
retry count
recovery count
duplicate upload count
cancellation count
```

Do not add a new observability framework if existing project stack already provides required mechanism.

---

## 31. Testing requirements

Refactor is incomplete without deterministic tests protecting business and architecture risks.

### 31.1 Domain unit tests

`ImportFile`:

- valid registration;
- checksum format invariant;
- invalid size/required metadata;
- no processing state exists on aggregate.

`ProcessingJob`:

- every allowed transition;
- every forbidden transition;
- counters invariant;
- progress cannot decrease;
- terminal completion rules;
- cancellation semantics;
- retry semantics;
- attempt number/trigger behavior;
- retry limit.

### 31.2 Application tests

Upload:

- returns/registers QUEUED without running customer processing;
- cleans temp object on validation failure;
- duplicate same owner returns conflict/no new job;
- different owner same checksum accepted;
- storage failure classification;
- DB duplicate race cleanup.

Runner:

- valid-only file -> COMPLETED;
- mixed valid/invalid -> COMPLETED_WITH_ERRORS;
- system failure -> FAILED;
- batch failure preserves previous committed batches;
- progress cadence;
- safe cancellation;
- retry starts counters from zero;
- report published only for completed-with-errors.

### 31.3 PostgreSQL/Testcontainers integration tests

Required for:

- `(owner, checksum)` uniqueness;
- claim race: multiple workers, exactly one winner;
- ProcessingAttempt uniqueness/concurrency;
- stale recovery atomicity;
- duplicate external-id tracker semantics;
- customer atomic upsert insert/update counts;
- concurrent same `externalId` last-write-wins behavior;
- Flyway migration/backfill correctness.

### 31.4 API tests

- upload is 202;
- Operator own resource allowed;
- cross-owner resource returns 404;
- Admin access rules;
- list filters/pagination bounds;
- retry/cancel HTTP semantics;
- report availability/expiry semantics;
- response DTOs do not expose persistence/domain objects.

### 31.5 Streaming/resource tests

Tests MUST prove code does not call whole-file load APIs.

Provide a non-default load/performance verification for large input approaching required scale:

```text
up to 1,000,000 rows
up to configured 500 MB
512 MB demo heap target
```

Do not place a 500 MB fixture in git.

Generate data/stream during benchmark or integration profile.

### 31.6 Architecture verification

At final state, scans/tests MUST confirm no production code remains under:

```text
fileimport/adapter/**
fileimport/application/port/**
customer/adapter/**
customer/application/port/**
```

and no types ending in new legacy architecture ceremony:

```text
*UseCase
*RepositoryPort
*PersistenceAdapter
*StoragePort
```

No new dependency/framework is required solely to implement this check.

---

## 32. Migration/cutover rules

Refactor MUST be incremental, not big-bang.

During migration temporary coexistence of legacy/target packages is allowed only when:

1. new code does not add Hexagonal ceremony;
2. each migrated caller uses target dependency direction;
3. tests remain green after each coherent step;
4. old classes are removed as soon as no caller remains;
5. no duplicated business implementation is kept long-term.

Do not maintain two independent processors or two independent job state machines.

Schema contract happens only after new model is proven and backfill verified.

---

## 33. Required implementation sequence

This is dependency order, not permission to skip verification gates.

```text
A. characterize current behavior/tests
        |
B. create target domain model + new schema expand migrations
        |
C. create customer bounded context + batch upsert boundary
        |
D. migrate ImportFile persistence/storage/CSV packages to DDD names
        |
E. change upload to register ImportFile + QUEUED ProcessingJob + 202
        |
F. implement atomic worker claim + ProcessingJobRunner
        |
G. progress/report/customer batch flow
        |
H. retry/cancel/recovery/query APIs
        |
I. backfill/cutover customer provenance
        |
J. remove legacy processing columns/packages/types
```

Do not start J before callers/schema verification for B-I passes.

---

## 34. MUST NOT list for AI agents

Agent MUST NOT:

1. only rename folders and call refactor complete;
2. keep counters/status in ImportFile;
3. run file processing inside upload HTTP request;
4. call Customer SQL/persistence from fileimport;
5. call `customer.infrastructure` from fileimport;
6. create `UploadFileUseCase` replacement interface;
7. add new `Port`/`Adapter` naming ceremony;
8. create repository per ProcessingAttempt;
9. use per-row DB save for customer import;
10. load full file/report/rows into memory;
11. wrap entire file processing in one transaction;
12. update progress every row;
13. retry business validation failures;
14. publish partial report for FAILED/CANCELLED attempt;
15. make Redis the duplicate correctness boundary;
16. allow two workers to claim same job;
17. reset/overwrite old attempt history on retry;
18. invent new broker/framework without explicit requirement;
19. edit old Flyway migrations;
20. delete legacy columns/data before backfill verification;
21. log full customer PII/file data;
22. bypass i18n/error-code rules;
23. copy auth-specific classes blindly into fileimport;
24. use `auth` as shared utility dumping ground.

---

## 35. Definition of Done

Refactor được coi là hoàn thành chỉ khi tất cả điều kiện sau đúng:

### Architecture

- [ ] `fileimport` follows `api/application/domain/infrastructure` semantic layers.
- [ ] `customer` exists as separate bounded context.
- [ ] no `fileimport/adapter/**` remains.
- [ ] no `fileimport/application/port/**` remains.
- [ ] no new `*UseCase`, `*RepositoryPort`, `*PersistenceAdapter` ceremony.
- [ ] `fileimport` does not import `customer.infrastructure`.
- [ ] application layers do not import JPA/Spring Data/JdbcTemplate/storage SDK/Redis SDK.

### Domain

- [ ] `ImportFile` owns immutable stored-file metadata only.
- [ ] `ProcessingJob` owns lifecycle/counters/progress/retry/cancel.
- [ ] `ProcessingAttempt` preserves append-only attempt history.
- [ ] all legal/illegal state transitions are unit-tested.
- [ ] Customer persistence source is `lastImportJobId`, not file ID.

### Upload

- [ ] upload stores/validates/registers only.
- [ ] success is 202 with `fileId`, `jobId`, `QUEUED` status.
- [ ] same-owner duplicate creates no second file/job.
- [ ] DB unique constraint is final duplicate correctness boundary.
- [ ] temp objects are cleaned on failure/duplicate race.

### Processing

- [ ] worker claim is atomic.
- [ ] pipeline is streaming and bounded.
- [ ] default logical batch size is 1000, configurable.
- [ ] default max in-flight batches is 4, configurable.
- [ ] one customer-upsert transaction per batch.
- [ ] previously committed batches survive later batch failure.
- [ ] progress/heartbeat cadence matches requirement.
- [ ] cancellation stops only at safe point.
- [ ] retry/recovery restarts original file from beginning and preserves history.

### Reports/API/security

- [ ] report exists only for COMPLETED_WITH_ERRORS.
- [ ] Operator can access only own resources; cross-owner is 404.
- [ ] Admin capabilities match requirement.
- [ ] job list/detail/progress/retry/cancel/report capabilities exist.
- [ ] error enums live in application layer and i18n keys exist EN + VI.
- [ ] logs contain no full customer PII/file contents/secrets.

### Persistence/migration

- [ ] new Flyway migrations are append-only.
- [ ] existing ImportFile rows are backfilled to ProcessingJob.
- [ ] legacy customer provenance is migrated to job ID.
- [ ] old processing columns are removed only after cutover verification.
- [ ] PostgreSQL integration tests prove unique/claim/upsert/recovery behavior.

### Verification

Agent MUST run before claiming completion:

```bash
./mvnw spotless:apply
./mvnw spotless:check
./mvnw verify
```

Final response/PR MUST report:

```text
what changed
which business invariants are now protected
schema migrations added
legacy types removed
commands/tests executed and results
known deviations from RULE/ARCHITECTURE/AGENTS, if any
```

No success claim without passing verification evidence.

---

## 36. Acceptance scenarios — critical path

### AC-01 Upload does not process synchronously

Given valid CSV
When Operator uploads
Then object is stored and validated
And one ImportFile + one QUEUED ProcessingJob are committed
And API returns 202
And no Customer row is written before worker executes the job.

### AC-02 Duplicate race

Given two concurrent uploads with same owner and same bytes
When both reach duplicate registration
Then exactly one canonical ImportFile/ProcessingJob exists
And loser receives duplicate conflict
And loser temporary object is cleaned.

### AC-03 Atomic claim

Given one QUEUED job and multiple worker instances
When all attempt claim concurrently
Then exactly one worker obtains PROCESSING ownership
And exactly one RUNNING attempt exists.

### AC-04 Partial commit

Given multiple valid batches
And batch N fails with system DB error
Then batches 1..N-1 remain committed
And batch N rolls back
And job/attempt become FAILED
And retry can replay from beginning without duplicate Customer creation.

### AC-05 Validation errors are not system failure

Given file with valid and invalid rows
When worker finishes
Then valid rows are upserted
And all validation issues are written to final report
And invalidRows counts source rows, not issue count
And job is COMPLETED_WITH_ERRORS, not FAILED.

### AC-06 Cooperative cancellation

Given PROCESSING job
When owner/Admin cancels
Then API moves job to CANCELLATION_REQUESTED and returns 202
And worker stops scheduling new batches
And current batch reaches safe completion/rollback
And job/attempt become CANCELLED
And no final report is published.

### AC-07 Recovery

Given worker crashes after committed batches and stale heartbeat remains
When recovery executes
Then old RUNNING attempt is closed safely
And job becomes retryable/queued through approved recovery transition
And next claim creates RECOVERY attempt
And processing restarts from original file beginning.

### AC-08 Ownership privacy

Given Operator A requests Operator B job ID
Then application returns 404
And no existence information is leaked.

### AC-09 Large-file bounded memory

Given a generated stream near supported row/file limits
When worker processes it
Then memory remains bounded by configured pipeline capacity
And implementation never materializes full file/all rows/full report in heap.

---

## 37. Final architectural picture

```text
                         HTTP
                          |
                          v
                fileimport.api
                          |
                          v
              fileimport.application
                 /        |        \
                /         |         \
               v          v          v
      fileimport.domain  capabilities  customer.application
            ^              ^                   |
            |              |                   v
            |              |            customer.domain
            |              |                   ^
            |              |                   |
 fileimport.infrastructure |        customer.infrastructure
        persistence        |
        storage -----------+
        csv ---------------+
        coordination ------+
        scheduling -> calls application
```

Primary rule:

> `ImportFile` tells us **what file was accepted**. `ProcessingJob` tells us **what processing is allowed and what happened**. `customer` owns **what customer data becomes persisted**. Infrastructure only answers **how technical work is performed**.
