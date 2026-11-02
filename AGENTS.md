# Agent guide

Always-loaded contract for AI agents and contributors. `CLAUDE.md` is a symlink
to this file. Load a skill from `skills/` only when the task matches its
trigger; never load every skill up front.

## Project

> Project-specific section. Replace it when reusing this guide in another
> repository; everything below "Shared conventions" stays the same.

- **Service:** Customer CSV File Processing Service — upload a customer CSV,
  validate it, process it in background jobs, report results.
- **Base package:** `com.vandunxg.file_processing`
- **Modules:** `auth`, `fileimport`, `customer`, `configuration` (shared web
  config).
- **Stack:** Java 21, Spring Boot (version pinned by `pom.xml` parent),
  PostgreSQL + Flyway, Redis (`common-cache`), RabbitMQ (`common-amqp`),
  S3-compatible object storage, `vandunxg-common` modules.
- **Requirements:** `docs/requirement/` is the approved business behavior;
  `99-master-BA-SRS.md` resolves conflicts. Load `customer-import-requirements`
  before changing upload, CSV validation, jobs, retry/cancel, reports,
  recovery, or retention.
- **Architecture overview:** `ARCHITECTURE.md`.
- **Project guardrails:**
  - Another owner's file, job, or report returns `404`, never `403`.
  - Never log customer rows or unmasked customer email/phone.

## Shared conventions

### Precedence

1. Approved business requirements (see Project).
2. Convention skills in `skills/`.
3. `vandunxg-common` for available shared APIs.
4. Generic framework knowledge.

Never silently reinterpret a requirement or rule; raise the conflict.

### Rule strength

- **MUST** — correctness, security, boundaries, production reliability. Not
  mergeable without an approved exception.
- **SHOULD** — default; deviate only with a stated reason in the PR.
- **MAY** — optional.

### Always-on guardrails

- Pragmatic Modular DDD: `api` / `application` / `domain` / `infrastructure`
  per module. No `adapter/*`, `port/*`, `*UseCase`, `*Port`, `*Adapter`.
- Smallest design that keeps real boundaries; no abstraction for an imagined
  future. No CQRS, Event Sourcing, WebFlux, or new major runtime dependency
  without an approved requirement. Do not override Spring Boot BOM versions
  without a verified reason.
- Stream unbounded files and results; bound every executor, queue, page, retry.
- Enforce authorization and ownership on the backend.
- Never log or expose secrets, tokens, or unmasked PII.
- Database constraints are the final guard for races and uniqueness.
- Every table and entity carries audit columns (`created_by`, `created_at`,
  `last_modified_by`, `last_modified_at`) and `deleted_at` — no exceptions.
- Lombok: never `@Data` or `@Value` (enforced by `lombok.config`);
  `@EqualsAndHashCode` on identity for aggregates/entities; `@ToString` never over sensitive
  fields. Details in `java-style`. DTOs are records.

### Skill routing

| Task | Skill |
|---|---|
| New module/package/class placement, interfaces, cross-module calls | `modular-architecture` |
| Aggregates, value objects, domain repositories, domain exceptions, time | `domain-modeling` |
| Naming, records, Lombok, Optional, modern Java | `java-style` |
| Error enums, exceptions, i18n keys, HTTP statuses | `error-handling` |
| MapStruct and layer-to-layer conversion | `object-mapping` |
| Entities, repositories, soft delete, transactions, JPA performance | `jpa-persistence` |
| Schema changes and SQL migrations | `flyway-migration` |
| Controllers, DTOs, pagination, OpenAPI | `rest-api` |
| Properties, env vars, secrets | `app-configuration` |
| Authn/authz, ownership, tokens, untrusted input | `backend-security` |
| Log statements and sensitive data | `logging` |
| File streaming, executors, jobs, retries, timeouts | `async-processing` |
| Writing or fixing tests | `testing` |
| Reusing shared utilities and plumbing | `vandunxg-common` |
| Commit, push, finish a change | `git-workflow` |
| Review or refactor | `code-quality` |

### Working method

1. Read the relevant requirement and acceptance criteria before changing
   behavior.
2. For unfamiliar or multi-file code, use CodeGraph (`codegraph_explore`)
   before reading files; for a local one-file change, read the file directly.
   Source and tests win over a stale index.
3. Check `vandunxg-common` before writing cross-cutting code.
4. Keep domain invariants out of controllers; add or update tests for behavior
   changes.
5. Run `./mvnw spotless:apply && ./mvnw verify` before claiming completion.
