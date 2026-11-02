---
name: modular-architecture
description: Use when creating a module or package, placing a new class, adding an interface or abstraction, splitting services, calling another module, or deciding whether a Command/Query/Result type is needed in a Spring modular monolith.
metadata:
  version: "0.0.1"
  author: vandunxg
  scope: shared
  category: architecture
  stack: java-21, spring-boot-4
---

# Modular architecture (Pragmatic Modular DDD)

Modular monolith, packaged by business module, pragmatic DDD inside each
module. **Not** Hexagonal/ports-and-adapters and **not** strict Clean
Architecture.

Every abstraction must answer: *which boundary or business problem makes this
necessary?* No answer → do not create it.

```text
api             "What does the caller want?"
application     "What workflow must happen?"
domain          "What does the business allow?"
infrastructure  "How is the technical capability implemented?"
```

## Module layout

`<base-package>/<module>/` with four semantic layers. This is a dependency
boundary, not a folder template: create only what the module needs.

```text
<module>/
├── api/                 controllers, dto/request, dto/response, mapper
├── application/         <Module>Properties, service/, capability/, command/,
│                        query/, result/, exception/
├── domain/              model/, <Aggregate>Repository, policy/, event/,
│                        service/, exception/
└── infrastructure/      persistence/, cache/, messaging/, storage/, email/,
                         security/, client/, scheduling/, bootstrap/, config/
```

- **MUST NOT** create `adapter/in|out` or `port/in|out` packages.
- **MUST NOT** create empty packages or placeholder types to match a diagram.
- `infrastructure` is split by **technology/capability**, never by direction.
- Schedulers, AMQP listeners, and startup bootstrap live in
  `infrastructure/<technology>/` and call an application service.
- `infrastructure/config` wires beans only; no business rules.

## Dependency rules

| From             | May depend on                                         |
|------------------|-------------------------------------------------------|
| `api`            | `application`; `domain` types only when response mapping needs them |
| `application`    | `domain`; Spring transaction/component annotations    |
| `infrastructure` | `application`, `domain`                               |
| `domain`         | nothing outside itself (no Spring, Jackson, servlet, HTTP, JPA) |

Forbidden: `application` → `api` or any infrastructure type (JPA entities,
Spring Data repositories, HTTP/broker/storage clients); module A → module B's
`infrastructure` or persistence model.

## Complexity levels — pick the thinnest that fits

1. **CRUD / thin capability:** `api → application → infrastructure`; add
   `domain` only when there is behavior to protect.
2. **Core business capability (default):** aggregate, invariants, one
   repository per aggregate root, value objects where meaningful.
3. **Complex workflow:** add only what is needed — domain events, policies,
   domain services, outbox, process managers.

A Level 1 capability **MUST NOT** be built as Level 3.

## Cross-module calls

A module owns its data. Call the other module's **application service**
directly; use a domain/application event only when async decoupling has real
value. Never read another module's repository or persistence model. Do not add
a cross-module interface when a direct service call suffices.

## Interfaces without ceremony

A concrete application service is the default; controllers, listeners, and
schedulers call the concrete class. Create an interface only for:

- an aggregate repository contract (in `domain`, implemented in `infrastructure`);
- an external provider: storage, email, distributed cache, event publisher,
  crypto, HTTP service;
- more than one implementation that actually exists;
- a deliberately stable inter-module contract, with a stated reason.

"Easier to mock", "DDD says so", and "we may swap it later" are not reasons.
**MUST NOT** create `<Capability>UseCase` interfaces or one interface per class.

## Name the meaning, not the pattern

| Do not use                      | Use                    |
|---------------------------------|------------------------|
| `UserRepositoryPort`            | `UserRepository`       |
| `EmailSenderPort`               | `EmailSender`          |
| `StoragePort`                   | `FileStorage`          |
| `UserPersistenceAdapter`        | `JpaUserRepository`    |
| `BcryptPasswordHasherAdapter`   | `BcryptPasswordHasher` |
| `MailServiceEmailSenderAdapter` | `SmtpEmailSender`      |

## Command / Query / Result

Not required per use case. Create one when: several callers share the use case;
the HTTP contract must evolve independently; the input carries business meaning
(normalization, value objects); the workflow is long enough that a named input
clarifies it; or the type is reused outside HTTP. A `Command` wrapping one
`UUID` adds a file and removes nothing.

`<Capability>CommandService` + `<Capability>QueryService` is the default split
for modules with meaningful read and write paths, optional for small ones. It is
code organisation, not CQRS. Never introduce CQRS, Event Sourcing, or Sagas
without an approved requirement.

Related: `domain-modeling`, `jpa-persistence`, `java-style` (naming table).
