# Architecture contract - `file_processing`

This document defines the target architecture and package structure for
`file_processing`. It is the architecture entry point for AI coding agents and
human contributors. Detailed coding, security, logging, testing, and library
rules remain in their owning documents instead of being duplicated here.

Read this file before creating a module, moving code across layers, adding an
abstraction, or refactoring legacy Hexagonal packages.

<!-- prettier-ignore -->
> [!IMPORTANT]
> Target architecture: **Pragmatic Modular DDD** inside one modular monolith.
> Existing `adapter/*`, `application/port/*`, `*UseCase`, `*RepositoryPort`,
> and `*PersistenceAdapter` code is legacy implementation pending migration.
> It is not a template for new code.

## Source of truth and reading order

Use project documents in this order:

1. `AGENTS.md` - business behavior, product requirements, and invariants.
2. `ARCHITECTURE.md` - module boundaries, layers, and dependency direction.
3. `RULE.md` - implementation and engineering rules.
4. `LIBRARY.md` - reusable APIs from `vandunxg-common`.
5. Relevant feature specs under `docs/`.
6. Existing source and tests as implementation evidence.

Business behavior wins when documents conflict. Do not silently reinterpret a
requirement to make a preferred design fit.

## Architecture target

The project uses:

```text
Modular Monolith
        +
Package by business module / bounded context
        +
Pragmatic Domain-Driven Design
```

It does not use strict Hexagonal Architecture, strict Clean Architecture,
mandatory CQRS, or one-interface-per-class conventions.

The design goal is simple:

```text
api             What does the caller want?
      |
      v
application     What workflow must happen?
      |
      v
domain          What does the business allow?
      ^
      |
infrastructure  How is the technical capability implemented?
```

Architecture complexity must come from business complexity, not templates.
Every abstraction must protect a real boundary or solve a real problem.

## Business modules

A business module owns a cohesive capability, language, data, and invariants.
Current requirements define these major areas:

```text
file_processing
|
+-- auth
|   Authentication, authorization, user/session/role security concerns
|
+-- fileimport
|   Import file lifecycle, processing jobs, attempts, reports, retry,
|   cancellation, recovery, progress, and retention
|
+-- customer
|   Customer identity, normalized customer data, and import-driven updates
|
`-- configuration
    Application-wide technical configuration only
```

`fileimport` and `customer` remain separate boundaries even when one workflow
touches both. `auth` must not become a generic shared-code dumping ground.

Create a new top-level module only when it has distinct business ownership,
language, invariants, or lifecycle.

## Standard module structure

Business modules live under:

```text
src/main/java/com/vandunxg/file_processing/<module>/
```

Use four semantic layers:

```text
<module>/
+-- api/
|   +-- controllers
|   +-- request/response DTOs
|   `-- API mappers
|
+-- application/
|   +-- command/query services
|   +-- commands/queries/results when useful
|   `-- application exceptions
|
+-- domain/
|   +-- aggregates/entities/value objects
|   +-- aggregate repositories
|   +-- policies/domain services
|   +-- domain events when justified
|   `-- domain exceptions
|
`-- infrastructure/
    +-- persistence
    +-- cache
    +-- messaging
    +-- storage
    +-- external clients/email/security
    +-- scheduling/bootstrap
    `-- technical configuration
```

This tree is a reference shape, not a mandatory package list. Do not create
empty packages to match a diagram.

## Layer responsibilities

Each layer has one primary responsibility. Detailed naming and implementation
rules remain in `RULE.md`.

### API

`api` owns external contracts:

- REST controllers;
- request/response DTOs;
- transport validation;
- HTTP status, headers, and OpenAPI concerns;
- mapping between transport and application contracts.

It must not contain business invariants, JPA queries, repository logic, Redis,
RabbitMQ, MinIO, or transaction orchestration.

Normal flow:

```text
HTTP -> Controller -> Application service
```

Controllers normally depend on concrete application services. Do not create an
inbound `UseCase` interface only to forward one controller call.

### Application

`application` owns use-case orchestration:

- authorization for the requested operation;
- loading aggregates;
- calling domain behavior;
- coordinating repositories and technical capabilities;
- transaction boundaries;
- event publication when required;
- mapping domain/application failures to the project error contract.

It must not implement persistence technology or duplicate aggregate invariants.

Typical write flow:

```text
Application service
    |
    +-- check use-case preconditions
    +-- load aggregate(s)
    +-- call domain behavior
    +-- persist aggregate(s)
    `-- publish required event(s)
```

`Command`, `Query`, and `Result` records are optional. Use them when they create
a real application boundary or reusable contract.

### Domain

`domain` owns business meaning and correctness:

- aggregate roots and entities;
- value objects;
- business state machines and enums;
- invariant-preserving behavior;
- aggregate repositories;
- domain policies/services/events when the business requires them.

Prefer business behavior such as:

```text
job.claim(...)
job.requestCancellation(...)
job.complete(...)
user.verifyEmail(...)
role.assignPermission(...)
```

Do not leak HTTP, servlet, Redis, RabbitMQ, MinIO, or other infrastructure
concepts into the domain model.

Direct JPA mapping on a domain model is allowed only when persistence shape and
domain shape align and the mapping does not distort domain behavior. The
normative decision rule lives in `RULE.md`.

### Infrastructure

`infrastructure` implements technical capabilities, for example:

- Spring Data JPA and database-specific queries;
- Redis/Redisson;
- MinIO/S3;
- RabbitMQ;
- SMTP/email;
- JWT/password hashing;
- outbound HTTP clients;
- schedulers/bootstrap listeners;
- technology-specific configuration and metrics integration.

Infrastructure may implement application/domain contracts where a real
boundary exists. It must not define business invariants.

## Dependency direction

Use this direction:

```text
api ----------> application ----------> domain
                    ^                    ^
                    |                    |
                    +--- infrastructure-+
```

Allowed:

```text
api            -> application
application    -> domain
infrastructure -> application, when implementing a technical contract
infrastructure -> domain, for persistence/domain integration
```

Forbidden:

```text
domain         -> application/api/infrastructure
application    -> api
module A       -> module B.infrastructure
module A       -> module B JPA entity/repository implementation
```

If a forbidden dependency seems necessary, redesign the boundary instead of
adding a shortcut.

### Allowed: `api` reads its own module's domain model

`api` may accept a domain model or enum from **its own module** and map it to a
response DTO. It must not call domain behavior, and must never serialize a
domain model as the response body.

This is deliberate, not a gap. A response DTO plus a MapStruct mapper with
`unmappedTargetPolicy = ERROR` already fails the build when an aggregate field
is renamed, so inserting a `Result` record that mirrors the response one-for-one
moves where the compiler reports that error without changing what it catches.
Per **Interface decision rule**, add a `Result` only when it carries
composition or computation the aggregate does not have — `LoginResult` (tokens
plus expiry from the issuer) and `SessionResult` (`current` relative to the
caller's session) qualify; a field-for-field copy of the response does not.

### Allowed: `api` referencing a JPA entity as a paging sort model

`@ValidatePaging(sortModel = X.class)` builds its allow-list by reflecting over
`@jakarta.persistence.Column` fields, so it only accepts a JPA entity. Domain
models carry no persistence annotations and would produce an empty allow-list
that rejects every `sortBy`. Use the entity, and keep the reference confined to
that annotation.

## Module ownership and communication

Every business concept has one owner:

```text
auth       -> User, Role, Session, authentication state
fileimport -> ImportFile, ProcessingJob, ProcessingAttempt, reports
customer   -> Customer and customer business identity
```

Do not bypass ownership by importing another module's infrastructure package.
Expose an application/domain capability intentionally.

Bad:

```text
fileimport.application
    -> customer.infrastructure.persistence.JpaCustomerRepository
```

Preferred:

```text
fileimport.application
    -> customer.application.CustomerImportService
```

For large imports, a specialized batch capability is valid when it protects
performance and transaction semantics. Do not replace a required batch API with
per-row calls only to keep the architecture visually pure.

## Aggregate and repository rules

An aggregate is a business consistency boundary.

- mutate state through aggregate behavior;
- enforce invariants before persistence;
- keep child entities behind the aggregate root;
- reference other aggregates by identity unless stronger consistency is
  required;
- do not create repositories for child entities owned exclusively by an
  aggregate root.

Repository names describe the aggregate or implementation technology:

```text
UserRepository
ProcessingJobRepository
CustomerRepository
JpaUserRepository
```

Do not use architecture-pattern suffixes as the default:

```text
*RepositoryPort
*PersistenceAdapter
```

## Persistence decision

Do not mechanically create separate domain/entity/mapper models.

Use this rule:

```text
Domain model ~= persistence model
        |
        +--> direct JPA mapping may be acceptable

Domain and persistence models differ materially
        |
        +--> separate persistence model + mapper
```

Separate models only for concrete reasons such as legacy schemas, conflicting
persistence relationships, multiple representations, or reporting/read models.

Never expose JPA entities directly from controllers.

## Transaction boundaries

Application services are the default transaction boundary for business writes.

- domain objects do not open transactions;
- controllers do not own long transactions;
- avoid slow external I/O inside DB transactions;
- use optimistic locking, explicit locking, atomic SQL, or idempotency when
  concurrency correctness requires it;
- document multi-aggregate transaction behavior explicitly.

For file processing, transaction scope must stay bounded by logical work units
or batches. Never wrap an entire large import in one database transaction.

## Cross-module workflows and events

Prefer synchronous application calls when the caller needs the result now and
asynchronous delivery adds no real value.

Use events when eventual consistency is acceptable and decoupled retries or
multiple independent consumers provide a concrete benefit.

Do not introduce messaging merely to make an internal call appear more "DDD".

## High-volume file-processing constraint

Architecture must preserve bounded resource usage and recoverability:

```text
stored file
   |
   v
job claim
   |
   v
stream parse
   |
   v
normalize + validate
   |
   v
bounded logical batches
   |
   +--> customer upsert
   +--> error-report stream
   +--> progress / heartbeat
   `--> cancellation safe points
   |
   v
terminal job state
```

Do not create unbounded queues, futures, threads, or in-memory row collections.
`AGENTS.md` remains authoritative for business and non-functional constraints.

## Interface decision rule

An interface is not the default design unit.

Create one when it protects a real module/provider boundary, has multiple real
implementations, isolates an external side effect, or represents an
independently valuable stable contract.

Do not create an interface only because:

- "DDD requires it";
- "Hexagonal uses ports";
- every service should have an interface;
- mocking feels easier;
- a hypothetical future implementation may appear.

Prefer capability-oriented names:

```text
UserRepository
FileStorage
EmailSender
TokenIssuer
JpaUserRepository
S3FileStorage
SmtpEmailSender
RabbitAuditPublisher
```

Avoid default architecture-pattern names:

```text
*UseCase
*Port
*RepositoryPort
*PersistenceAdapter
*InboundAdapter
*OutboundAdapter
```

## Legacy Hexagonal migration guard

Existing source may still contain:

```text
adapter/in
adapter/out
application/port/in
application/port/out
*UseCase
*RepositoryPort
*PersistenceAdapter
```

Treat these as legacy implementation.

When changing legacy code:

1. Do not add new Hexagonal ceremony.
2. Preserve behavior and public contracts first.
3. Migrate only the approved boundary/scope.
4. Keep tests passing through each migration step.
5. Remove obsolete types only after callers migrate.
6. Do not perform repository-wide package renames inside unrelated work.

Temporary coexistence of legacy and target packages is acceptable during a
controlled migration. New dependency violations are not.

## Architecture complexity levels

Use the smallest level that protects the business.

### Level 1 - simple CRUD/support

Use only the layers that add value. A simple capability may need `api`,
`application`, and `infrastructure` without a rich domain model.

### Level 2 - core business

Use `api`, `application`, `domain`, and `infrastructure`, with aggregates,
invariants, repositories, and value objects where they carry business meaning.

### Level 3 - complex workflow

Add advanced patterns only when justified, such as domain events, outbox,
process manager, saga, specification, or explicit idempotency models.

Never promote a simple feature to Level 3 to make the code look enterprise.

## Architecture review checklist

Before implementation or review, verify:

- Which module owns this behavior?
- Which aggregate owns the invariant?
- Is transport logic limited to `api`?
- Is orchestration in `application`?
- Is non-trivial business behavior in `domain`?
- Are technical details isolated in `infrastructure`?
- Does any module import another module's infrastructure implementation?
- Is every new interface justified by a real boundary?
- Is every duplicated model/mapper justified?
- Is the transaction boundary explicit and bounded?
- Are concurrency and idempotency requirements preserved?
- Does the change extend legacy Hexagonal structure unnecessarily?
- Did the change reuse existing capabilities from `LIBRARY.md`?
- Do tests protect the changed invariant and boundary?

## Required AI-agent workflow

For non-trivial work, AI agents must:

```text
1. Read relevant AGENTS.md / feature requirements.
2. Identify the owning module and aggregate.
3. Read ARCHITECTURE.md.
4. Read relevant RULE.md sections.
5. Search LIBRARY.md before creating reusable infrastructure.
6. Inspect current source, callers, migrations, and tests.
7. Define dependency and transaction boundaries in the plan.
8. Implement the smallest correct change.
9. Run required verification.
10. Report architecture deviations explicitly.
```

Historical implementation plans never override this contract or `RULE.md`.

## Documentation ownership

Keep project knowledge separated:

```text
AGENTS.md        business behavior and requirements
ARCHITECTURE.md  architecture shape and dependency boundaries
RULE.md          coding and engineering contract
LIBRARY.md       shared-library API contract
docs/**          feature specs, designs, plans, historical records
```

Do not copy large sections between these files. Link to the owning document.
Duplicated rules drift and eventually contradict each other.

## Recommended size for AI rule files

There is no universal byte limit. The goal is to keep mandatory context small
enough that an AI agent can read it completely while retaining task context.

Recommended targets for this repository:

```text
ARCHITECTURE.md   10-20 KB   focused architecture contract
RULE.md           15-30 KB   engineering rules and verification gates
AGENTS.md         business-only; move feature detail to docs/**
LIBRARY.md        search/read only relevant sections; it may remain large
```

Treat about **30 KB** as a practical soft ceiling for an always-read `RULE.md`.
When it grows beyond that, move tutorials, long examples, feature-specific
requirements, library catalogs, and historical migration detail into focused
documents.

A good `RULE.md` keeps only high-value instructions that affect generated code:

- rule precedence and MUST/MAY strength;
- implementation constraints;
- naming and code-style rules;
- error/security/logging/transaction/testing rules;
- prohibited patterns;
- required verification gates.

Optimize rule files for **compliance**, not maximum completeness.

## Definition of architecture compliance

A change is compliant when:

- business behavior still matches `AGENTS.md` and approved specs;
- new code follows module-first Pragmatic DDD;
- dependency direction is valid;
- aggregate ownership is preserved;
- infrastructure does not drive business APIs;
- abstractions are justified by current requirements;
- no new Hexagonal ceremony is introduced;
- transaction and high-volume resource constraints remain safe;
- applicable `RULE.md` verification gates pass.

If a required feature cannot satisfy this contract, create an explicit
architecture decision/change request instead of hiding the violation.
