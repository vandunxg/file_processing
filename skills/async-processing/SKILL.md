---
name: async-processing
description: Use when handling file upload or download, streaming large content, creating executors, thread pools, queues, or background jobs, calling external services, or adding retries, timeouts, or idempotency.
metadata:
  version: "0.0.1"
  author: vandunxg
  scope: shared
  category: runtime
  stack: java-21, spring-boot-4
---

# Files, jobs, and external calls

Work is bounded, streaming, idempotent where required, and observable.

## File I/O

- Stream uploads and downloads. Never `MultipartFile.getBytes()`,
  `Files.readAllBytes()`, or any whole-file load for unbounded content.
- Enforce the maximum size before expensive processing.
- Validate content with trusted parsers or signatures, not the extension or
  client media type.
- Generate server-side storage names; never use raw user filenames as keys or
  paths. Normalize paths against traversal.
- Compute checksums while streaming when integrity or dedup needs them.
- Delete temporary files in `finally` or a managed lifecycle; use
  try-with-resources.

## Executors and jobs

- No unbounded executors, queues, page sizes, or retries.
- Size concurrency from CPU, memory, database pool, and downstream limits.
- Persist job state when work must survive restarts; define legal state
  transitions and test all of them.
- Make externally retried operations idempotent: idempotency key, unique
  constraint, or atomic claim.
- Claim work atomically: a conditional `UPDATE ... WHERE status = :from`
  (row count 1 = claimed), or `SELECT ... FOR UPDATE SKIP LOCKED` for queues
  polled by several workers.
- Every executor propagates MDC/trace context via a `TaskDecorator`; never use
  `CompletableFuture.*Async` without an explicit bounded executor.

## External calls and retries

- Every external call has a timeout.
- Retry only transient failures, with bounded attempts and backoff plus jitter.
- Never retry validation, authorization, or permanent `4xx` failures.
- Prevent retry storms with concurrency limits and circuit breaking where needed.
- Keep external calls outside database transactions (see `jpa-persistence`).
- Use `RestClient` for blocking HTTP; reserve `WebClient` for reactive/streaming.
