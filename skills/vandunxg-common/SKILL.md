---
name: vandunxg-common
description: Use before writing a utility, response wrapper, error type, paging DTO, auditing base class, mapper contract, cache, AMQP publisher, email sender, HTTP client, JWT or security filter, Jackson config, or i18n setup in a service that depends on the vandunxg-common library.
metadata:
  version: "0.0.1"
  author: vandunxg
  scope: shared
  category: library
  stack: java-21, spring-boot-4
---

# vandunxg-common reuse

Check here before hand-rolling cross-cutting code — it probably exists.
Verify signatures against the installed dependency version when a detail is
version-sensitive. Never guess an API.

## Mandatory reuse

| Need                  | Component                                      |
|-----------------------|------------------------------------------------|
| Auditable domain / JPA base | `AuditableDomain`, `AuditableEntity`     |
| Response envelope     | `Response<T>`, `PagingResponse<T>`, `PageDTO<T>` |
| Errors                | `ResponseError`, `ResponseException`, `ExceptionHandleAdvice` |
| Paging                | `PagingRequest`, `PagingQuery`, `@ValidatePaging` |
| Current user          | `SecurityUtils`                                |
| IDs / hashing / dates / strings | `IdUtils.nextId()`, `HashUtils`, `DateUtils`, `StrUtils` |
| Jackson               | `MapperFactoryUtils.jacksonMapper()`           |
| Cache                 | `CacheService`, `@CacheAction`, `@CacheUpdate` |
| AMQP publishing       | `AmqpEventPublisher`                           |

## Module references — read only the one you need

| Need | Read |
|---|---|
| Install, module map, strings, dates, hashing, IDs, serialization | `references/utils.md` |
| Responses, errors, DTOs, validation, auditing | `references/models.md` |
| JPA helpers, SQL, paging base, cache | `references/persistence-and-cache.md` |
| AMQP and email | `references/messaging-and-email.md` |
| HTTP clients, JWT/web security, i18n, OpenAPI | `references/client-and-web.md` |

## Rules

- Keep module-specific helpers local. Promote code into the library only when
  two real consumers need the same stable abstraction.
- Library availability does not authorize new runtime infrastructure (AMQP,
  Redis, email) the project has not approved.
