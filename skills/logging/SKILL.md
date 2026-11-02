---
name: logging
description: Use when adding or changing log statements, choosing a log level, logging exceptions, adding breadcrumbs around external calls, or handling sensitive data such as tokens, emails, or phone numbers in logs.
metadata:
  version: "0.0.1"
  author: vandunxg
  scope: shared
  category: observability
  stack: java-21, spring-boot-4
---

# Logging

Logs support investigation. They are not a second error transport and must not
leak sensitive data.

## Format

```java
@Slf4j(topic = "ROLE-SERVICE")
@Service
public class RoleCommandService {
  void deactivate(UUID roleId) {
    log.info("[deactivate] role deactivated roleId={}", roleId);
  }
}
```

- Topic: stable `UPPER-KEBAB-CASE`, normally `<MODULE>-<FEATURE>`.
- Message: `[methodName] lowercase description key={} key={}`.
- SLF4J placeholders only; never string concatenation.
- Pass the exception as an **extra** last argument, never into a `{}`
  placeholder (that formats it as text and can lose the stack trace):
  `log.error("[store] object storage write failed fileId={}", fileId, ex);`
- Structured fields where JSON logging is enabled:
  `log.atInfo().addKeyValue("jobId", id).log("[process] job completed");`
  (`logging.structured.format.console: ecs|logstash`; Micrometer Tracing adds
  `traceId`/`spanId`).

## Context across threads

MDC and trace context are thread-local. Executors need a `TaskDecorator`
(Spring's `ContextPropagatingTaskDecorator` with Micrometer, or copy the MDC
map); `CompletableFuture.supplyAsync(...)` without an explicit executor runs on
the common pool and loses it. See `async-processing`.

## Log once, at the useful boundary

- No log before every `throw`; never log the same exception at every layer.
- Expected validation, not-found, and business conflicts need no log by default.
- Log an unexpected technical failure once, where there is operational context.
- Domain models never log.
- Fragile boundaries (external HTTP, object storage, retries, parsers, atomic
  claims) **SHOULD** emit concise start/outcome breadcrumbs.

## Levels

| Level   | Use                                                         |
|---------|-------------------------------------------------------------|
| `error` | Unexpected failure needing operator attention; include cause |
| `warn`  | Recoverable anomaly, retry, conflict, degraded dependency   |
| `info`  | Business-visible lifecycle event with operational value     |
| `debug` | Investigation detail, off in production by default          |
| `trace` | Local diagnosis only                                        |

## Never log

- passwords, hashes, JWTs, refresh/reset tokens, authorization headers;
- secrets and storage credentials;
- full customer/user records, request bodies, or file contents;
- full email addresses or phone numbers — mask them.

Opaque UUIDs may be logged in full. Never `log.info("entity={}", entity)` when
`toString()` may leak data. A **record's** `toString()` prints every component
and `@ToString.Exclude` cannot apply — log IDs only, or override `toString()` on
records carrying email, phone, or tokens. Never `System.out.println` or `printStackTrace`.
