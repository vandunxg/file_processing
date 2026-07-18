# Coding Rules — file_processing

> **This file is the coding contract for every AI agent (Claude, Gemini,
> Cursor, Copilot, …) and every human contributor working on this repository.**
> Business behaviour lives in [`AGENTS.md`](./AGENTS.md).
> Reusable base classes and utilities live in [`LIBRARY.md`](./LIBRARY.md).
> Vietnamese mirror: [`RULE_vi.md`](./RULE_vi.md).

Precedence when rules disagree: `AGENTS.md` (business) > this file (how to code)
> your own habits. Do not silently reinterpret a rule. Raise a change request.

---

## 1. Read the codebase first (mandatory workflow)

Before you write, edit, or plan any code, do these in order.

1. **CodeGraph** (fast, index-backed). This repo is indexed under `.codegraph/`.
   - MCP tools: call `codegraph_explore` first — one call returns the verbatim
     source of the relevant symbols plus the call paths between them. Fall back
     to `codegraph_node` to read a whole file or one symbol with its callers.
   - Shell fallback: `codegraph explore "<question or symbols>"` and
     `codegraph node <symbol-or-file>`.
2. **`LIBRARY.md`** — scan for reusable base classes / utils before writing
   your own. If it exists in the common lib, reuse it.
3. **`AGENTS.md`** — read the relevant business section before changing
   behaviour.
4. Only fall back to `grep`, `find`, or `Read` for details that CodeGraph did
   not surface.

✅ Do: `codegraph_explore "LoginService AuthController User"` before touching
auth code.
❌ Don't: open random files and guess the flow, or duplicate a util that
already exists in `com.vandunxg.common.utils.*`.

---

## 2. Technology baseline (frozen)

| Layer          | Technology                                                    |
|----------------|---------------------------------------------------------------|
| Language       | Java 21                                                       |
| Framework      | Spring Boot 4.1.x (parent already pinned in `pom.xml`)        |
| Build          | Maven (wrapper `./mvnw`)                                      |
| Persistence    | PostgreSQL + Spring Data JPA + Hibernate                      |
| Migrations     | Flyway (`src/main/resources/db/migration`)                    |
| Security       | Spring Security + JWT (access + rotating refresh)             |
| Cache          | `com.vandunxg.common:common-cache` (Redis-backed)             |
| Messaging      | `com.vandunxg.common:common-amqp` (only when a spec requires) |
| Mapping        | MapStruct (compile-time). ModelMapper is **not** allowed for new code. |
| Logging        | SLF4J 2.0.17 via Lombok `@Slf4j`                              |
| Formatting     | Spotless + Google Java Format 1.27.0                          |
| i18n           | Spring `MessageSource` at `classpath:i18n/messages`           |
| Testing        | JUnit 5 + Mockito + AssertJ + Testcontainers                  |
| API docs       | Springdoc OpenAPI (`springdoc-openapi-starter-webmvc-ui`)     |

**Do not add** a new framework, messaging system, or ORM without an explicit
change request. No Kafka, no CQRS framework, no Event Sourcing, no native
image, no Lombok-free rewrite. See `AGENTS.md` §"Patterns to reject".

---

## 3. Package layout (Hexagonal, per module)

Every business module lives under
`src/main/java/com/vandunxg/file_processing/<module>/`. The `auth` module is
the reference layout. Copy this shape for new modules.

```
<module>/
├── adapter/
│   ├── in/
│   │   └── web/
│   │       ├── <Xxx>Controller.java          # thin REST controllers
│   │       ├── dto/
│   │       │   ├── request/  <Xxx>Request.java
│   │       │   └── response/ <Xxx>Response.java
│   │       └── mapper/       <Xxx>WebMapper.java
│   ├── out/
│   │   └── persistence/
│   │       ├── entity/       <Xxx>Entity.java, Jpa<Xxx>Repository.java
│   │       ├── mapper/       <Xxx>PersistenceMapper.java
│   │       └── <Xxx>PersistenceAdapter.java  # implements *RepositoryPort
│   └── shared/                                # module-local helpers
├── application/
│   ├── port/
│   │   ├── in/               <Xxx>UseCase.java   (interface)
│   │   └── out/              <Xxx>RepositoryPort.java, <Xxx>...Port.java
│   ├── service/              <Xxx>Service.java   (@Service, implements UseCase)
│   ├── command/              <Xxx>Command.java   (write-side input)
│   └── query/                <Xxx>Query.java     (read-side input, extends PagingQuery)
├── domain/
│   ├── model/                <Xxx>.java   (extends AuditableDomain, no Spring/JPA)
│   └── exception/            <Xxx>ErrorCode.java (implements ResponseError)
└── configuration/            <Xxx>Configuration.java (@Configuration Spring beans)
```

Dependency rule (hexagonal):

- `domain/` depends on **nothing** except `com.vandunxg.common.models.domain`,
  `.exception`, `.error`, `common.utils`, and `java.*`.
- `application/` depends on `domain/` and common models; **never** on adapters,
  Spring Web, or JPA annotations.
- `adapter/` depends on `application/` and `domain/`. Adapters are the only
  place `@RestController`, `@Entity`, `@Repository`, `RestClient`, etc. may
  appear.
- `configuration/` wires beans; keep it thin.

✅ Do: put `AuthController` in `auth/adapter/in/web/`.
❌ Don't: put JPA annotations on a `domain/model` class.

---

## 4. Naming conventions

| Concept                       | Suffix / pattern              | Location                            |
|-------------------------------|-------------------------------|-------------------------------------|
| Domain aggregate/entity       | `User`, `AuditLog`            | `domain/model/`                     |
| Domain enum                   | `UserStatus`, `OperationType` | `domain/model/`                     |
| Module error catalog          | `<Module>ErrorCode`           | `domain/exception/`                 |
| Inbound use case (interface)  | `<Xxx>UseCase`                | `application/port/in/`              |
| Outbound port (interface)     | `<Xxx>RepositoryPort`, `<Xxx>NotifierPort` | `application/port/out/` |
| Use case implementation       | `<Xxx>Service` (`@Service`)   | `application/service/`              |
| Write-side input              | `<Xxx>Command`                | `application/command/`              |
| Read-side input               | `<Xxx>Query` (extends `PagingQuery`) | `application/query/`         |
| REST controller               | `<Xxx>Controller`             | `adapter/in/web/`                   |
| HTTP request DTO              | `<Xxx>Request` (extends `Request`) | `adapter/in/web/dto/request/`  |
| HTTP response DTO             | `<Xxx>Response` (extends `BaseResponse` when auditable) | `adapter/in/web/dto/response/` |
| Web mapper                    | `<Xxx>WebMapper`              | `adapter/in/web/mapper/`            |
| JPA entity                    | `<Xxx>Entity` (extends `AuditableEntity`) | `adapter/out/persistence/entity/` |
| Spring Data repository        | `Jpa<Xxx>Repository`          | `adapter/out/persistence/entity/`   |
| Persistence adapter           | `<Xxx>PersistenceAdapter`     | `adapter/out/persistence/`          |
| Persistence mapper            | `<Xxx>PersistenceMapper` (implements `EntityMapper<D,E>`) | `adapter/out/persistence/mapper/` |
| Spring configuration          | `<Xxx>Configuration`          | `configuration/`                    |
| Scheduled job                 | `<Xxx>Scheduler` / e.g. `SystemGC` | `configuration/` or feature pkg |
| Test class                    | `<ClassName>Test` (unit) / `<ClassName>IT` (integration) | `src/test/java` mirror |

Method names are `camelCase` verbs; boolean methods start with `is`/`has`/
`can`. Enum values are `SCREAMING_SNAKE_CASE`. Constants are `static final`
in `SCREAMING_SNAKE_CASE`.

---

## 5. Domain model rules

Every persistent domain aggregate extends `AuditableDomain` from
`com.vandunxg.common.models.domain`. Fixed Lombok combo:

```java
package com.vandunxg.file_processing.auth.domain.model;

import java.time.Instant;
import java.util.UUID;

import com.vandunxg.common.models.domain.AuditableDomain;
import lombok.*;
import lombok.experimental.SuperBuilder;

@Getter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Setter(AccessLevel.PRIVATE)
@EqualsAndHashCode(callSuper = false)
public class User extends AuditableDomain {

  private UUID id;
  private String username;
  private String password;
  private String email;
  private Instant deletedAt;
}
```

Rules:

- `@Setter(AccessLevel.PRIVATE)` — mutation happens through domain behaviour
  methods, not setters called from outside.
- `@SuperBuilder` because it extends `AuditableDomain`.
- IDs are `UUID`, generated with `IdUtils.nextId()` (see `LIBRARY.md`).
- Soft-delete uses `deletedAt` (`Instant`), never a boolean flag.

✅ Do: add domain behaviour methods (e.g. `user.deactivate()`) on the aggregate.
❌ Don't: import `jakarta.persistence.*`, `org.springframework.*`, or
`javax.validation.*` inside `domain/`.

---

## 6. Exception and error handling ⭐

The common lib already provides the full pipeline. Do not reinvent it.

### 6.1 Define a module error catalog

Every module owns one enum implementing
`com.vandunxg.common.models.error.ResponseError`. This enum lives in
`<module>/domain/exception/`.

```java
package com.vandunxg.file_processing.auth.domain.exception;

import org.springframework.http.HttpStatus;
import com.vandunxg.common.models.error.ResponseError;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum AuthErrorCode implements ResponseError {

  INVALID_CREDENTIALS(40101, "auth.error.invalid_credentials", HttpStatus.UNAUTHORIZED),
  ACCOUNT_LOCKED     (40301, "auth.error.account_locked",     HttpStatus.FORBIDDEN),
  USER_NOT_FOUND     (40401, "auth.error.user_not_found",     HttpStatus.NOT_FOUND),
  REFRESH_TOKEN_REUSED(40102,"auth.error.refresh_token_reused",HttpStatus.UNAUTHORIZED);

  private final Integer code;      // business code returned to client
  private final String messageKey; // i18n key, resolved by LocaleStringService
  private final HttpStatus http;

  @Override public String getName()    { return name(); }
  @Override public String getMessage() { return messageKey; }
  @Override public int getStatus()     { return http.value(); }
  @Override public Integer getCode()   { return code; }
}
```

Numeric code convention: `{httpStatus}{2-digit module code}` (auth = `01`,
file-import = `02`, customer = `03`, …). Reserve `xx000` for "unspecified in
this class".

### 6.2 Log the context *before* you throw

Every `throw` that ends a request must be preceded by a log line that captures
the observable context, so the trace is complete without needing to re-run the
request. This applies at both the domain and the application layer.

- **Level:** `warn` for business failure (bad credentials, forbidden action,
  not found), `error` for a genuine system failure that also throws.
- **Format** follows §8.2: `[methodName] <what failed> key=value key=value`.
  Include the identifiers a future investigator needs (user id, resource id,
  attempt number, remote IP, trace id when it is not already in MDC). Never
  the password, token, or full customer row.
- Do **not** re-log the same failure at every layer that re-throws — one log
  where the decision is made is enough. Higher layers may log context they
  add (e.g. controller adds the request path).

```java
// application/service/LoginService.java
public LoginResult login(LoginCommand cmd) {
  var user = userRepository.findByUsername(cmd.getUsername()).orElse(null);
  if (user == null) {
    log.warn("[login] user not found username={} ip={}", cmd.getUsername(), cmd.getIpAddress());
    throw new AuthDomainException(AuthErrorCode.INVALID_CREDENTIALS);
  }
  if (!passwordEncoder.matches(cmd.getPassword(), user.getPassword())) {
    log.warn("[login] invalid password userId={} ip={}", user.getId(), cmd.getIpAddress());
    throw new AuthDomainException(AuthErrorCode.INVALID_CREDENTIALS);
  }
  if (user.getStatus() == UserStatus.INACTIVE) {
    log.warn("[login] inactive account userId={} status={}", user.getId(), user.getStatus());
    throw new AuthDomainException(AuthErrorCode.ACCOUNT_LOCKED, user.getId());
  }
  ...
}
```

**Also log breadcrumbs around code that is known to be fragile** — external
HTTP calls, MinIO / S3 I/O, DB race points, retry loops, parser boundaries.
Log the input state *before* the risky call at `info` or `debug`, then log
the outcome (`success`, `retry`, `failed`) after it. Investigators should be
able to reconstruct the failure from log alone.

```java
log.debug("[claimJob] attempting atomic claim jobId={} workerId={}", jobId, workerId);
int updated = jobRepository.tryClaim(jobId, workerId);
if (updated == 0) {
  log.warn("[claimJob] already taken by another worker jobId={}", jobId);
  throw new JobDomainException(JobErrorCode.CONCURRENT_CLAIM);
}
log.info("[claimJob] claimed jobId={} workerId={}", jobId, workerId);
```

### 6.3 Domain exceptions belong to the domain

The `domain/` layer must not depend on `com.vandunxg.common.models.exception`
symbols by their generic name. Each module owns a **domain-scoped exception
class** that extends `ResponseException` so it stays wire-format-compatible
with `ExceptionHandleAdvice`, but reads as a domain concept in domain code.

```java
// auth/domain/exception/AuthDomainException.java
package com.vandunxg.file_processing.auth.domain.exception;

import com.vandunxg.common.models.error.ResponseError;
import com.vandunxg.common.models.exception.ResponseException;

public class AuthDomainException extends ResponseException {

  public AuthDomainException(ResponseError error) {
    super(error);
  }

  public AuthDomainException(ResponseError error, Object... params) {
    super(error, params);
  }

  public AuthDomainException(String message, Throwable cause, ResponseError error,
                             Object... params) {
    super(message, cause, error, params);
  }
}
```

Usage split:

| Layer         | Throw this                                                                                             |
|---------------|--------------------------------------------------------------------------------------------------------|
| `domain/`     | `<Module>DomainException(<Module>ErrorCode.XXX, …)` — pure domain vocabulary.                          |
| `application/`| Same domain exception when the failure is a domain rule. `ResponseException` from common lib when it is a cross-cutting application concern that is not owned by any single domain (e.g. `BadRequestError.INVALID_INPUT`). |
| `adapter/`    | Never throw a `ResponseException` yourself; either let the domain throw, or wrap upstream failures inside the adapter and re-throw as the module's domain exception. |

Because `AuthDomainException extends ResponseException`, the existing
`ExceptionHandleAdvice` catches it via the parent type — **no new advice
class is needed** and the on-the-wire `ErrorResponse` shape is identical.
This is exactly what "same standard format" means.

If a module has two disjoint error catalogues (e.g. `file-import` splits into
`ImportFileErrorCode` and `ProcessingJobErrorCode`), you may add a second
domain exception (`ImportFileDomainException`, `ProcessingJobDomainException`)
— both still extend `ResponseException`.

### 6.4 i18n messages

Every `messageKey` must exist in **both** `messages.properties` (English) and
`messages_vi.properties` (Vietnamese):

```properties
# src/main/resources/i18n/messages.properties
auth.error.invalid_credentials=Invalid username or password
auth.error.account_locked=Account {0} is locked
```

```properties
# src/main/resources/i18n/messages_vi.properties
auth.error.invalid_credentials=Sai tên đăng nhập hoặc mật khẩu
auth.error.account_locked=Tài khoản {0} đang bị khoá
```

### 6.5 Rules

✅ Do:
- Define one `<Module>ErrorCode implements ResponseError` per module.
- Define one `<Module>DomainException extends ResponseException` per module.
- **Log context at `warn`/`error` immediately before the `throw`** — one log
  line per decision point, with the identifiers needed to trace it later.
- Add breadcrumb logs at `info`/`debug` around fragile code paths (external
  I/O, retry, race points) so a failure is reconstructable from log alone.
- Add every new error code to the module's enum, never to another module's.
- Let `ExceptionHandleAdvice` (auto-configured by `common-web`) format the
  response.

❌ Don't:
- Throw a bare `ResponseException` from `domain/` — use `<Module>DomainException`.
- Throw `IllegalArgumentException`, `RuntimeException`, or `NullPointerException`
  to signal a business error.
- `throw new AuthDomainException(...)` **without a log line just above it**.
- Log the same failure again at each layer that re-throws — one log, at the
  decision point, is enough.
- Write your own `@RestControllerAdvice` unless you first extend
  `ExceptionHandleAdvice`.
- Catch `Exception e` in a service to `return null` or a sentinel value.
- Hard-code an English message in Java — always use an i18n key.
- Include a token, password, or full customer row in error params or log.

---

## 7. MapStruct conventions (mapping)

**Use MapStruct. Do not use ModelMapper, BeanUtils, or reflection.** MapStruct
generates the mapper implementation at compile time, so there is no runtime
reflection, mismatches surface as compile errors, and the generated code is
inspectable in `target/generated-sources/annotations/`.

### 7.1 Maven wiring

Add MapStruct alongside Lombok in `pom.xml`. Because both use annotation
processors, they must both be listed in `annotationProcessorPaths` **in the
correct order** (`lombok-mapstruct-binding` bridges them).

```xml
<properties>
  <mapstruct.version>1.6.3</mapstruct.version>
  <lombok-mapstruct-binding.version>0.2.0</lombok-mapstruct-binding.version>
</properties>

<dependencies>
  <dependency>
    <groupId>org.mapstruct</groupId>
    <artifactId>mapstruct</artifactId>
    <version>${mapstruct.version}</version>
  </dependency>
</dependencies>

<!-- inside maven-compiler-plugin executions/default-compile/configuration -->
<annotationProcessorPaths>
  <path>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
  </path>
  <path>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok-mapstruct-binding</artifactId>
    <version>${lombok-mapstruct-binding.version}</version>
  </path>
  <path>
    <groupId>org.mapstruct</groupId>
    <artifactId>mapstruct-processor</artifactId>
    <version>${mapstruct.version}</version>
  </path>
</annotationProcessorPaths>
```

Remove the `org.modelmapper:modelmapper` dependency and the
`modelmapper.version` property in the same commit.

### 7.2 Mapper shape

Every mapper is an **interface** annotated `@Mapper(componentModel = "spring")`
— MapStruct emits the implementation as a Spring bean; inject the interface
where you need it.

Domain ↔ JPA entity mapper implements `EntityMapper<D, E>` from `common-models`
so the four required methods stay uniform across the codebase:

```java
// adapter/out/persistence/mapper/UserPersistenceMapper.java
package com.vandunxg.file_processing.auth.adapter.out.persistence.mapper;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

import com.vandunxg.common.models.mapper.EntityMapper;
import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.UserEntity;
import com.vandunxg.file_processing.auth.domain.model.User;

@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.ERROR,
    unmappedSourcePolicy = ReportingPolicy.WARN)
public interface UserPersistenceMapper extends EntityMapper<User, UserEntity> {

  @Override User toDomain(UserEntity entity);

  @Override
  @Mapping(target = "createdAt",  ignore = true)   // audit set by JPA lifecycle
  @Mapping(target = "lastModifiedAt", ignore = true)
  UserEntity toEntity(User domain);

  @Override List<User> toDomain(List<UserEntity> entities);
  @Override List<UserEntity> toEntity(List<User> domains);
}
```

Web mapper (DTO ↔ command / response) is a plain `@Mapper` interface — no
`EntityMapper` contract because there is no entity involved:

```java
// adapter/in/web/mapper/AuthWebMapper.java
@Mapper(componentModel = MappingConstants.ComponentModel.SPRING,
        unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface AuthWebMapper {

  @Mapping(target = "ipAddress", source = "ipAddress")
  LoginCommand toCommand(LoginRequest request, String ipAddress);

  LoginResponse toResponse(LoginResult result);
}
```

### 7.3 Rules

✅ Do:
- One mapper interface per adapter side: `<Xxx>PersistenceMapper` in
  `adapter/out/persistence/mapper/`, `<Xxx>WebMapper` in
  `adapter/in/web/mapper/`.
- `componentModel = "spring"` on every mapper.
- Set `unmappedTargetPolicy = ReportingPolicy.ERROR` so a forgotten field
  breaks the build, not production.
- Implement `EntityMapper<D, E>` for domain↔entity mappers.
- If two Lombok-generated classes need to be mapped, keep the
  `lombok-mapstruct-binding` processor path.

❌ Don't:
- `new ModelMapper()`, `ModelMapper` beans, or ModelMapper `TypeMap`
  configuration — remove existing usage as you touch files.
- `BeanUtils.copyProperties`, `Apache BeanUtils`, or hand-rolled reflection.
- Runtime-reflective mapping libraries in general.
- Manual field-by-field copy in a service (`domain.setX(entity.getX())`) —
  put it in the mapper.
- Instantiate a mapper with `new` — always inject the Spring bean.

---

## 8. Logging (SLF4J)

### 8.1 Declaration

Every class that logs uses Lombok:

```java
@Slf4j(topic = "AUTH-LOGIN")   // UPPER-KEBAB-CASE, describes the class/feature
@Service
public class LoginService implements LoginUseCase {
  ...
}
```

Topic examples already in the repo: `SYSTEM-UTIL`, `SYSTEM-GC`. Pattern:
`<MODULE>[-<FEATURE>]`. One topic per class; keep it stable.

### 8.2 Message format — **`[methodName] description key={} key={}`**

Always start the message with `[methodName]` (the actual Java method the log
statement is inside). Description is lowercase English, followed by named
parameters using `{}` placeholders. Values go as the trailing varargs.

```java
public void runSystemGC() {
  log.info("[runSystemGC] starting gc trigger");
  long start = System.currentTimeMillis();
  SystemUtil.gc();
  log.info("[runSystemGC] finished gc trigger durationMs={}", System.currentTimeMillis() - start);
}

public LoginResponse login(LoginCommand cmd) {
  log.info("[login] attempt username={}", cmd.getUsername());
  var user = userRepository.findByUsername(cmd.getUsername())
      .orElseThrow(() -> new ResponseException(AuthErrorCode.INVALID_CREDENTIALS));
  ...
  log.info("[login] success userId={} ip={}", user.getId(), cmd.getIpAddress());
  return response;
}
```

### 8.3 Levels

| Level   | Use for                                                              |
|---------|----------------------------------------------------------------------|
| `error` | Unexpected system failure that needs an operator; include the cause. |
| `warn`  | Recoverable anomaly, retry, cooperative cancellation.                |
| `info`  | Business-visible lifecycle events (login success, job started).      |
| `debug` | Verbose flow used during investigation. Off in prod by default.      |
| `trace` | Only in local debugging; must not fire in production log volume.     |

### 8.4 Sensitive data

**Never** log: JWT, password, refresh token, storage credential, full customer
row, full email, full phone number, request body of an upload.
**Always** mask: email as `a***@domain`, phone as `+84********99`, UUID may be
logged in full because it is opaque.

### 8.5 Rules

✅ Do: `log.error("[claimJob] failed to claim jobId={} attempt={}", jobId, attempt, e);`
❌ Don't:
- `System.out.println(...)`
- `e.printStackTrace()`
- `log.info("something happened: " + var)` (string concat instead of `{}`)
- `log.info("user: {}", user)` where `toString()` leaks the password field

---

## 9. Formatting — Spotless (mandatory before commit)

Spotless is wired to `mvn verify`. It will fail the build if a file drifts.

```
mvn spotless:apply    # reformat all Java files
mvn spotless:check    # verify only (what CI runs)
```

Configured rules (see `pom.xml`):

- Google Java Format 1.27.0
- Import order: `java, javax, jakarta, org, com`
- Remove unused imports
- Trim trailing whitespace
- End file with newline

Repo-wide (`.editorconfig`): UTF-8, LF, 4-space indent for `.java`, 2-space for
YAML.

✅ Do: run `mvn spotless:apply` right before `git add`.
❌ Don't: commit with `--no-verify`; disagreements with the formatter are a
change request against `pom.xml`, not a per-file exemption.

---

## 10. Reuse from the common library

**Before writing a new util, base class, mapper, DTO, or config: grep
[`LIBRARY.md`](./LIBRARY.md).** If the capability already exists in
`com.vandunxg.common:2.0.5`, use it. If you truly need a new one, propose it as
an addition to the common lib (bump version) instead of duplicating locally.

Non-exhaustive list of mandatory reuse:

| Need                                | Use this                                                             |
|-------------------------------------|----------------------------------------------------------------------|
| Auditable domain aggregate          | extend `com.vandunxg.common.models.domain.AuditableDomain`           |
| Auditable JPA entity                | extend `com.vandunxg.common.models.entities.AuditableEntity`         |
| HTTP request base                   | extend `com.vandunxg.common.models.dto.request.Request`              |
| Paged HTTP request                  | extend `com.vandunxg.common.models.dto.request.PagingRequest`        |
| HTTP response wrapper               | return `com.vandunxg.common.models.dto.response.Response<T>` / `PagingResponse<T>` |
| Auditable response body             | extend `com.vandunxg.common.models.dto.response.BaseResponse`        |
| Page result                         | `com.vandunxg.common.models.dto.PageDTO<T>`                          |
| Domain↔entity mapper contract       | implement `com.vandunxg.common.models.mapper.EntityMapper<D, E>`     |
| Search/paging query base            | extend `com.vandunxg.common.persistence.query.PagingQuery`           |
| Custom JPA base repository          | extend `com.vandunxg.common.persistence.repository.custom.BaseEntityRepositoryCustom` |
| Business error                      | throw `com.vandunxg.common.models.exception.ResponseException` with a `ResponseError` enum |
| Current user                        | `com.vandunxg.common.web.support.SecurityUtils.getCurrentUserLoginId()` |
| i18n lookup                         | `com.vandunxg.common.web.i18n.LocaleStringService.getMessage(...)`   |
| UUID generation                     | `com.vandunxg.common.utils.IdUtils.nextId()`                         |
| SHA-256 checksum                    | `com.vandunxg.common.utils.HashUtils.sha256(...)`                    |
| Date parsing / formatting           | `com.vandunxg.common.utils.DateUtils`                                |
| String helpers, email/phone format  | `com.vandunxg.common.utils.StrUtils`                                 |
| Jackson mapper                      | `com.vandunxg.common.utils.MapperFactoryUtils.jacksonMapper()`       |
| Cache                               | inject `com.vandunxg.common.cache.service.CacheService` or use `@CacheAction` / `@CacheUpdate` |
| AMQP publisher                      | `com.vandunxg.common.amqp.publisher.AmqpEventPublisher`              |

See [`LIBRARY.md`](./LIBRARY.md) for the full catalog with method signatures.

---

## 11. Configuration and properties

- Every configurable value lives in `application.yaml` (or a profile file)
  and reads through `${ENV_VAR:default}`.
- Namespace: `app.<module>.<key>` — e.g. `app.security.jwt.secret`,
  `app.gc.cron-time`.
- Never commit real secrets. `.env.example` documents required variables.
- Feature toggles use plain properties (`app.<module>.<feature>.enabled`), not
  a heavyweight flag framework.

Bind properties with a typed `@ConfigurationProperties` record in
`configuration/` — not by scattering `@Value` across services.

```java
@ConfigurationProperties(prefix = "app.security.jwt")
public record JwtProperties(String issuer, String audience, String secret,
                            Duration accessTokenExpiration,
                            Duration refreshTokenExpiration,
                            Duration clockSkew) {}
```

---

## 12. Persistence

- JPA entity is a **separate class** from the domain model, lives in
  `adapter/out/persistence/entity/`, extends `AuditableEntity`.
- Table and column names are `snake_case`.
- Use `@Version` for optimistic locking on aggregates that can race.
- `application.yaml` runs Hibernate in `ddl-auto: validate` — **every** schema
  change ships as a Flyway migration.
- Migration filename: `V{yyyyMMddHHmm}__{snake_case_description}.sql` in
  `src/main/resources/db/migration/`. Timestamp prefix avoids version conflicts
  between developers.
- Migrations are append-only; never edit a merged migration. Fix forward with
  a new `V…__` file.
- Prefer database constraints (`UNIQUE`, `NOT NULL`, `CHECK`, foreign keys)
  as the correctness boundary; validation and locks are only optimizations.

### 12.1 Soft-delete — dùng `deleted_at` TIMESTAMPTZ, **không** dùng boolean

Mọi entity có soft-delete phải dùng cột `deleted_at TIMESTAMPTZ NULL` — **không
được** dùng `deleted BOOLEAN`. Ngay cả khi clone mô hình từ dự án khác (ví dụ
`be-v2` dùng `deleted boolean`), khi đưa vào project này phải chuyển thành
`deleted_at`.

Lý do:

- **Query theo index rẻ** — dùng partial index chỉ index bản ghi còn sống:
  `CREATE INDEX <tbl>_active_idx ON <tbl> (<cols>) WHERE deleted_at IS NULL;`.
  Index nhẹ vì không chứa bản ghi đã xoá.
- **Thông tin thời gian** — biết chính xác *khi nào* bản ghi bị xoá, phục vụ
  audit, retention cleanup, và tính năng khôi phục (restore).
- **Retention rẻ** — `DELETE FROM <tbl> WHERE deleted_at < now() - INTERVAL
  '30 days'`.
- **Sort/paging** — làm màn hình "trash" theo `deleted_at DESC` dễ dàng.

Quy ước cụ thể:

- JPA entity: `@Column(name = "deleted_at") private Instant deletedAt;` — kiểu
  `Instant`, không bọc `Boolean` hoặc dùng `@Convert`.
- Domain model (subclass `AuditableDomain`): `private Instant deletedAt;`.
- Domain method soft-delete: `void delete(Instant now)`, `boolean isDeleted()`
  trả `deletedAt != null`.
- Migration: `deleted_at TIMESTAMPTZ NULL`, kèm partial index cho query business.
- Query mặc định: `WHERE deleted_at IS NULL`.
- Không đặt tên biến `isDeleted`/`deleted` cho cột thời gian — luôn `deletedAt`.

```java
@Getter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Setter(AccessLevel.PRIVATE)
@EqualsAndHashCode(callSuper = false)
public class Role extends AuditableDomain {

  private UUID id;
  private String code;
  private String name;
  private Instant deletedAt;   // ✅ chuẩn convention

  public void delete(Instant now) {
    if (this.deletedAt != null) return;
    this.deletedAt = now;
  }

  public boolean isDeleted() {
    return this.deletedAt != null;
  }
}
```

```sql
-- Flyway migration
ALTER TABLE role ADD COLUMN deleted_at TIMESTAMPTZ NULL;
CREATE INDEX role_active_code_idx ON role (code) WHERE deleted_at IS NULL;
CREATE INDEX role_deleted_at_idx  ON role (deleted_at) WHERE deleted_at IS NOT NULL;
```

✅ Do: `deleted_at TIMESTAMPTZ`, `deletedAt: Instant?`, partial index
`WHERE deleted_at IS NULL`.
❌ Don't:
- `deleted BOOLEAN`.
- Cột tên `deleted` mà lại kiểu timestamp.
- `is_deleted` (bỏ tiền tố `is_` vì đây là timestamp không phải boolean).
- Query business không kèm điều kiện `deleted_at IS NULL`.

✅ Do: `V202607170930__create_users_table.sql`.
❌ Don't: modify `V202607170900__…sql` after it has been applied anywhere.

---

## 13. API layer and i18n

Controllers are **thin**: they receive a `Request`, build a `Command`/`Query`,
call the use case, and wrap the result in `Response<T>`.

```java
@RestController
@RequestMapping("${app.api.prefix}/${app.api.version}/auth")
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-CONTROLLER")
public class AuthController {

  private final LoginUseCase loginUseCase;
  private final AuthWebMapper webMapper;

  @PostMapping("/login")
  public Response<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                       HttpServletRequest http) {
    log.info("[login] username={} ip={}", request.getUsername(), http.getRemoteAddr());
    var command = webMapper.toCommand(request, http.getRemoteAddr());
    var result  = loginUseCase.login(command);
    return Response.of(webMapper.toResponse(result));
  }
}
```

Rules:

- No business logic in controllers. Validation annotations only.
- Base path uses the config values `app.api.prefix` and `app.api.version`.
- Every user-facing message resolves through `i18n/messages*.properties`. Keys
  are `snake.dotted` and grouped by module (`auth.error.*`, `file.info.*`).
- Response body is always a `Response<T>` (or `PagingResponse<T>`) — never a
  raw domain or entity.

---

## 14. OpenAPI / Springdoc + DTO schema

### 14.1 One central configuration class

Add `springdoc-openapi-starter-webmvc-ui` to `pom.xml`. All global OpenAPI
metadata and security schemes live in **one** `@Configuration` bean, never on
a controller.

```java
// configuration/OpenApiConfiguration.java
@Configuration
@OpenAPIDefinition(
    info = @Info(title = "File Processing API", version = "v1"),
    security = { @SecurityRequirement(name = OpenApiConfiguration.BEARER_AUTH) }
)
@SecurityScheme(
    name = OpenApiConfiguration.BEARER_AUTH,
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    bearerFormat = "JWT"
)
public class OpenApiConfiguration {
  public static final String BEARER_AUTH = "bearerAuth";
}
```

### 14.2 Endpoint annotations

Because `@OpenAPIDefinition` above sets Bearer auth as the default, **public**
endpoints (login, refresh, health) opt out with `@SecurityRequirements` (empty):

```java
@Operation(summary = "Đăng nhập bằng username và password")
@SecurityRequirements                     // public — no bearer needed
@PostMapping("/login")
public Response<LoginResponse> login(@Valid @RequestBody LoginRequest req) { ... }

@Operation(
    summary = "Lấy thông tin người dùng hiện tại",
    security = @SecurityRequirement(name = OpenApiConfiguration.BEARER_AUTH))
@GetMapping("/me")
public Response<UserResponse> me() { ... }
```

Reference the constant `OpenApiConfiguration.BEARER_AUTH`; never re-type the
string literal in a controller.

### 14.3 DTOs describe their own schema

Validation is enforced by Jakarta Validation (`@NotBlank`, `@Size`, `@Email`,
`@Min`, `@Max`, …). `@Schema` **only** adds description and example — it is
not a validator.

```java
public record LoginRequest(

    @Schema(description = "Tên đăng nhập", example = "operator01",
            minLength = 4, maxLength = 100)
    @NotBlank
    @Size(min = 4, max = 100)
    String username,

    @Schema(description = "Mật khẩu", example = "StrongPassword@123",
            format = "password")
    @NotBlank
    String password) {}
```

If the DTO needs to inherit shared audit / correlation fields, use a class
extending `com.vandunxg.common.models.dto.request.Request` instead of a
record — same annotation rules apply.

### 14.4 Rules

✅ Do:
- Put `@OpenAPIDefinition` / `@SecurityScheme` on one Spring-managed bean.
- Use `@Schema` for description/example only; enforce constraints with
  Jakarta Validation.
- Return `Response<XxxResponse>` (a DTO), so the schema is stable.

❌ Don't:
- Sprinkle `@SecurityScheme` on multiple controllers.
- Use `@Schema(required = true)` **instead of** `@NotNull` / `@NotBlank` —
  Springdoc infers `required` from the validation annotations.
- Expose a JPA `@Entity` as the OpenAPI response type. It leaks columns,
  lazy proxies, and audit fields you didn't mean to publish.

---

## 15. Testing

- Framework: JUnit 5, Mockito, AssertJ. Integration: Testcontainers PostgreSQL.
- Location mirrors production: `src/test/java/.../auth/application/service/LoginServiceTest.java`.
- Naming:
  - `LoginServiceTest` — unit tests, no Spring context.
  - `LoginServiceIT` — integration tests, Spring context + Testcontainers.
- Method names describe the scenario:
  `login_returnsAccessToken_whenCredentialsValid()`,
  `login_throwsInvalidCredentials_whenPasswordWrong()`.
- Every new **behaviour** ships with a test. Refactors that change no observable
  behaviour do not need new tests; format-only changes never do.
- Use fixtures / builders, not JSON files, unless the test proves parsing.

Recommended coverage floor per module: 80% line, 100% for state machines and
authorization branches. Numbers are guidance, not a hard gate — reviewer's
judgment wins.

---

## 16. Git commit convention

Follow Conventional Commits: `type(scope): subject`.

| Type       | Use for                                            |
|------------|----------------------------------------------------|
| `feat`     | New user-visible capability                        |
| `fix`      | Bug fix                                            |
| `refactor` | Code change with no behaviour change               |
| `perf`     | Performance improvement                            |
| `docs`     | Docs only (this file, `AGENTS.md`, `LIBRARY.md`, README) |
| `test`     | Adding or fixing tests                             |
| `chore`    | Build config, dependency bumps, tooling            |
| `style`    | Formatting only (typically `spotless apply`)       |
| `ci`       | CI configuration                                   |
| `build`    | Maven, Docker, packaging                           |

- Subject is imperative, lowercase, no trailing period. Under 72 chars.
- Scope is optional but useful: `feat(auth): add refresh token rotation`.
- Body explains **why**, not what. Reference requirement IDs from `AGENTS.md`
  when applicable.
- Before every commit: `mvn spotless:apply && mvn verify`. Only override on
  explicit user request.
- Do not amend or force-push a shared branch without asking.

---

## 17. Anti-patterns (rejected in review)

Reject or revise any change containing these unless there is an approved
exception noted in the PR description.

1. Business logic in a controller.
2. An interface for every class (see AGENTS.md — "do not create an interface
   for every class").
3. Domain model importing Spring, JPA, or Jackson annotations.
4. Custom `@RestControllerAdvice` that duplicates `ExceptionHandleAdvice`.
5. Throwing `IllegalArgumentException` / `RuntimeException` / `NullPointerException`
   to signal a business error.
6. `catch (Exception e) { log.error(...); return null; }` — swallowing failure.
7. `BeanUtils.copyProperties(...)`, hand-rolled reflection copying, or any
   runtime-reflective mapping library (`ModelMapper`, Dozer, Orika).
8. A mapper written as a `@Component` class instead of a MapStruct
   `@Mapper(componentModel = "spring")` interface.
9. `@Autowired` on a field. Use constructor injection (`@RequiredArgsConstructor`).
10. `System.out.println` / `e.printStackTrace()`.
11. String concatenation in log messages (`"user " + id`), unmasked PII, or
    logging a full JWT / password / customer row.
12. Hard-coded English string returned to the client — must go through i18n.
13. Duplicating a util that already lives in `com.vandunxg.common.utils.*` —
    reuse it or bump the common lib version.
14. Editing a Flyway migration after it has been merged.
15. Setting `spring.jpa.hibernate.ddl-auto` to anything other than `validate`.
16. Committing with `--no-verify` or skipping Spotless.
17. `@Schema(required = true)` used to enforce a constraint instead of
    `@NotNull` / `@NotBlank`.
18. Returning a JPA `@Entity` from a controller.
19. Storing an `@OpenAPIDefinition` or `@SecurityScheme` on a controller
    instead of `OpenApiConfiguration`.
20. `MultipartFile.getBytes()`, `Files.readAllBytes()`, `readAllLines()`,
    unbounded executors, and other patterns already listed in `AGENTS.md`
    §"Patterns to reject".

---

## Quick checklist before you commit

- [ ] `codegraph_explore` / `LIBRARY.md` searched — no duplication.
- [ ] Package layout matches §3 for any new class.
- [ ] Domain has no Spring/JPA imports.
- [ ] New error → `<Module>ErrorCode`, thrown as `<Module>DomainException`
      (from domain) or `ResponseException` (from application cross-cutting),
      i18n key present in both `messages.properties` and `messages_vi.properties`.
- [ ] A `log.warn` / `log.error` line is present **immediately before** every
      new `throw` that ends a request, with the identifiers needed to trace it.
- [ ] Fragile code (external I/O, retry, race points) has breadcrumb logs
      before and after the risky call.
- [ ] `@Slf4j(topic = "…")` on the class; logs start with `[methodName]` and
      use `{}` placeholders; no PII / tokens.
- [ ] Mapper is a MapStruct `@Mapper(componentModel = "spring")` interface;
      no `ModelMapper`, `BeanUtils`, or reflection copying.
- [ ] DTO has Jakarta Validation + `@Schema`. No JPA entity in the response.
- [ ] New endpoint: security either default (bearer) or explicit
      `@SecurityRequirements` for public.
- [ ] Schema change → new Flyway migration `V{yyyyMMddHHmm}__*.sql`.
- [ ] New behaviour has a test.
- [ ] `mvn spotless:apply && mvn verify` is green.
- [ ] Commit follows `type(scope): subject`.
