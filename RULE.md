# Coding rules — `file_processing`

> This file is the coding contract for every AI agent and human contributor
> working on this repository. Business behavior lives in
> [`AGENTS.md`](./AGENTS.md). Reusable shared components live in
> [`LIBRARY.md`](./LIBRARY.md). The Vietnamese mirror is
> [`RULE_vi.md`](./RULE_vi.md).

When rules conflict, use this precedence:

1. `AGENTS.md` for business behavior and product requirements.
2. This file for implementation and engineering rules.
3. `LIBRARY.md` for available shared APIs.
4. Personal preference.

Do not silently reinterpret a rule. Raise a change request when a rule no
longer fits the codebase.

---

## 1. Rule strength

This document uses three requirement levels.

- **MUST** protects correctness, security, architecture boundaries, or
  production reliability. A change that violates a MUST rule cannot be merged
  without an approved exception.
- **SHOULD** is the default approach. A different approach is allowed when the
  pull request explains why it is clearer or safer.
- **MAY** is optional guidance.

Prefer the smallest design that preserves the required boundaries. Do not add
an abstraction, interface, package, framework, or infrastructure component for
an imagined future requirement.

---

## 2. Read the codebase before changing it

Before planning or editing code, establish the current behavior and reuse
available components.

1. Use CodeGraph first for unfamiliar, cross-cutting, or multi-file changes.

- MCP: call `codegraph_explore` first, then `codegraph_node` when full source
  or callers are still needed.
- Shell fallback: `codegraph explore "<question or symbols>"` and
  `codegraph node <symbol-or-file>`.

2. Read the relevant business section in `AGENTS.md` before changing behavior.
3. Search `LIBRARY.md` before creating a shared utility, base class, mapper,
   DTO, repository helper, or configuration.
4. Read the affected source and tests directly. Current source code and tests
   are authoritative if a generated index is stale.
5. Use `grep`, `find`, or direct file reads for local details that CodeGraph did
   not surface.

For a local and obvious one-file change, direct source inspection is enough.
Do not run repository-wide discovery only to change a typo or a local constant.

---

## 3. Technology baseline

The project baseline is intentionally small and stable.

| Area        | Standard                                                  |
|-------------|-----------------------------------------------------------|
| Language    | Java 21                                                   |
| Framework   | Spring Boot 4.1.x, version pinned by the parent POM       |
| Build       | Maven wrapper, `./mvnw`                                   |
| Persistence | PostgreSQL, Spring Data JPA, Hibernate                    |
| Migration   | Flyway in `src/main/resources/db/migration`               |
| Security    | Spring Security, JWT access token, rotating refresh token |
| Cache       | `com.vandunxg.common:common-cache`                        |
| Messaging   | `com.vandunxg.common:common-amqp`, only when required     |
| Mapping     | MapStruct for non-trivial mappings                        |
| Logging     | SLF4J, version managed by the Spring Boot BOM             |
| Formatting  | Spotless and Google Java Format                           |
| i18n        | Spring `MessageSource` at `classpath:i18n/messages`       |
| Testing     | JUnit 5, Mockito, AssertJ, Testcontainers                 |
| API docs    | Springdoc OpenAPI 3.x for Spring Boot 4                   |

**MUST NOT** add a new ORM, messaging platform, mapping framework, CQRS
framework, event-sourcing framework, feature-flag platform, or other major
runtime dependency without an explicit change request.

Do not override dependency versions already managed by the Spring Boot BOM
unless a verified compatibility or security reason requires it.

---

## 4. Module structure and dependency direction

Business modules live under:

```text
src/main/java/com/vandunxg/file_processing/<module>/
```

Use this layout as a dependency boundary, not as a mandatory list of folders.
Create only the packages and types the module actually needs.

```text
<module>/
├── adapter/
│   ├── in/web/
│   │   ├── <Xxx>Controller.java
│   │   ├── dto/request/
│   │   ├── dto/response/
│   │   └── mapper/
│   └── out/
│       ├── persistence/
│       │   ├── entity/
│       │   ├── mapper/
│       │   └── <Xxx>PersistenceAdapter.java
│       └── client/
├── application/
│   ├── port/in/
│   ├── port/out/
│   ├── service/
│   ├── command/
│   ├── query/
│   ├── result/
│   └── exception/
├── domain/
│   ├── model/
│   ├── service/
│   └── exception/
└── configuration/
```

### 4.1 Dependency rules

- `domain` **MUST NOT** import Spring, JPA, Jackson, servlet, HTTP, or adapter
  classes.
- `application` **MAY** use Spring transaction and component annotations, but
  **MUST NOT** depend on controllers, JPA entities, Spring Data repositories,
  HTTP clients, or other adapters.
- `adapter` implements inbound and outbound boundaries and may depend on
  `application` and `domain`.
- `configuration` wires infrastructure and cross-cutting beans. Keep it thin.
- One business module **MUST NOT** reach into another module's adapter package.
  Communicate through an application port or an explicitly shared domain API.

### 4.2 Interfaces without ceremony

Create an interface when it protects a real boundary:

- an inbound use case is called by one or more inbound adapters;
- an outbound dependency can have multiple implementations or must be replaced
  in tests;
- a module boundary must be kept stable.

Do not create one interface per class or one use-case interface per trivial
method. Group cohesive operations when that keeps the API understandable.

Do not create empty packages or placeholder classes to match the reference
shape.

---

## 5. Naming and Java style

Use names that reveal business intent.

| Concept                | Convention                                |
|------------------------|-------------------------------------------|
| Domain aggregate       | `User`, `Role`, `ProcessingJob`           |
| Domain value object    | `EmailAddress`, `FileChecksum`            |
| Domain enum            | `UserStatus`, `JobStatus`                 |
| Inbound port           | `<Capability>UseCase`                     |
| Outbound port          | `<Xxx>RepositoryPort`, `<Xxx>StoragePort` |
| Application service    | `<Capability>Service`                     |
| Write input            | `<Action>Command`                         |
| Read input             | `<Action>Query`                           |
| Application output     | `<Action>Result`                          |
| Controller             | `<Xxx>Controller`                         |
| Request DTO            | `<Xxx>Request`                            |
| Response DTO           | `<Xxx>Response`                           |
| JPA entity             | `<Xxx>Entity`                             |
| Spring Data repository | `Jpa<Xxx>Repository`                      |
| Persistence adapter    | `<Xxx>PersistenceAdapter`                 |
| Configuration          | `<Xxx>Configuration`                      |
| Unit test              | `<ClassName>Test`                         |
| Integration test       | `<ClassName>IT`                           |

Additional conventions:

- Methods use camel-case verbs.
- Boolean methods start with `is`, `has`, or `can`.
- Enum constants and constants use `SCREAMING_SNAKE_CASE`.
- Avoid abbreviations unless they are established domain language.
- Keep methods focused. Prefer early returns over deep nesting.
- Prefer records for immutable commands, queries, results, and value objects.
- Do not use `Optional` as a field, DTO field, entity field, or parameter.
- Return an empty collection instead of `null`.
- Only paginated queries extend `PagingQuery`. Point lookups use plain records
  or direct identifiers.

---

## 6. Domain model

Domain objects express business state and behavior. They are not persistence or
HTTP models.

### 6.1 Construction and invariants

- Aggregates **MUST** preserve invariants through explicit constructors,
  factories, and behavior methods.
- Do not require a fixed Lombok annotation combination for every domain class.
- Do not add a no-argument constructor only for convenience. JPA constructors
  belong on JPA entities, not domain models.
- Builders **MAY** be used for tests or complex construction, but they **MUST
  NOT** bypass required invariants.
- Equality **MUST** follow domain identity semantics. Do not generate equality
  over mutable fields without reviewing the result.
- Mutation happens through business methods such as `activate()`, `delete()`,
  or `changeName()`, not public setters.

Example:

```java

@Getter
public final class Role extends AuditableDomain {

  private final UUID id;
  private String name;
  private RoleStatus status;
  private Instant deleteAt;

  private Role(UUID id, String name) {
    this.id = Objects.requireNonNull(id);
    this.name = requireValidName(name);
    this.status = RoleStatus.ACTIVE;
  }

  public static Role create(String name) {
    return new Role(IdUtils.nextId(), name);
  }

  public void rename(String newName) {
    this.name = requireValidName(newName);
  }

  public void delete(Instant now) {
    if (status == RoleStatus.ACTIVE) {
      throw new RoleRuleViolation(RoleRule.ROLE_MUST_BE_INACTIVE);
    }
    if (deleteAt == null) {
      deleteAt = Objects.requireNonNull(now);
    }
  }

  public boolean isDeleted() {
    return deleteAt != null;
  }
}
```

A pure domain exception **MUST NOT** contain an HTTP status or know the API
response format. Application exceptions translate domain failures into the
standard response contract.

### 6.2 Time

- Persist timestamps as `Instant`.
- Inject `Clock` into application code containing time-dependent behavior.
- Do not call `Instant.now()` directly in business logic that requires
  deterministic tests.
- Convert to a user time zone only at the API or presentation boundary.

---

## 7. Application errors and i18n

The common web library owns the final error response format. Modules own their
error catalog and localized messages.

### 7.1 Module-prefixed error names

Every application error enum constant **MUST** start with an uppercase module
prefix:

```text
<MODULE>_<ERROR_NAME>
```

Examples:

```text
AUTH_INVALID_CREDENTIALS
ROLE_IS_ACTIVE
FILE_UNSUPPORTED_MEDIA_TYPE
PROCESSING_JOB_NOT_FOUND
```

The prefix is mandatory because `ResponseError.getName()` is also the global
i18n key. Generic names such as `NOT_FOUND`, `INVALID_STATUS`, or
`ACCESS_DENIED` are not allowed.

### 7.2 Error enum

Each module owns an error enum in `application/exception` that implements
`ResponseError`.

```java

@Getter
@RequiredArgsConstructor
public enum RoleErrorCode implements ResponseError {

  ROLE_NOT_FOUND(40411, "Role not found", 404),
  ROLE_IS_ACTIVE(40913, "Role must be inactive before deletion", 409);

  private final Integer code;
  private final String message;
  private final int status;

  @Override
  public String getName() {
    return name();
  }
}
```

Rules:

- The enum constant name **MUST** include the module prefix.
- The i18n key **MUST** exactly match the enum constant name.
- The numeric business code **MUST** be unique across the repository.
- Keep the current integer format: `{httpStatus}{2-digit sequence}`.
- Sequence numbers are repository-wide for that HTTP status; do not restart the
  sequence in each module if that would create duplicates.
- Existing published numeric codes **MUST NOT** be renumbered without an API
  compatibility decision.
- The fallback message is English and **MUST NOT** contain secrets or PII.
- Use the closest semantically correct standard HTTP status.

Add a unit test that scans every `ResponseError` enum and fails when a numeric
code, enum name, or required i18n key is duplicated or missing.

### 7.3 i18n files

Every error name **MUST** exist in both files:

```text
src/main/resources/i18n/messages.properties
src/main/resources/i18n/messages_vi.properties
```

Example:

```properties
# messages.properties
ROLE_NOT_FOUND=Role not found
ROLE_IS_ACTIVE=Role must be inactive before deletion
```

```properties
# messages_vi.properties
ROLE_NOT_FOUND=Không tìm thấy vai trò
ROLE_IS_ACTIVE=Vai trò phải ở trạng thái không hoạt động trước khi xóa
```

Do not use dotted keys when the common exception handler resolves by
`error.getName()`.

### 7.4 Exceptions by layer

- Domain code throws a pure domain rule violation only when the domain object
  itself enforces the rule.
- Application services translate a domain violation or application failure
  into a module-specific exception that extends `ResponseException`.
- Adapters translate infrastructure-specific exceptions into a meaningful
  application/module error when callers can act on it. Preserve the original
  cause.
- Controllers normally do not create or translate business exceptions.
- Do not throw `IllegalArgumentException`, `RuntimeException`, or
  `NullPointerException` to signal a business failure.

Example:

```java
public final class RoleException extends ResponseException {

  public RoleException(RoleErrorCode error, Object... params) {
    super(error, params);
  }

  public RoleException(
    String message, Throwable cause, RoleErrorCode error, Object... params) {
    super(message, cause, error, params);
  }
}
```

### 7.5 HTTP statuses

Preferred application-defined statuses are:

- `200`, `201`, `202`, and `204` for success;
- `400`, `401`, `403`, `404`, `409`, `413`, `415`, and `429` for client
  failures;
- `500`, `502`, `503`, and `504` for server or dependency failures.

Framework-generated standard statuses such as `405` or `406` remain valid.
Using another standard status requires a clear semantic reason in the review;
it does not require mapping an unrelated condition to an incorrect status.

---

## 8. Logging and observability

Logs support investigation. They must not become a second error transport or a
source of sensitive-data leakage.

### 8.1 Declaration and format

Classes that log use SLF4J, normally through Lombok:

```java

@Slf4j(topic = "ROLE-SERVICE")
@Service
public class RoleService implements RoleManagementUseCase {
  // ...
}
```

Use a stable topic in `UPPER-KEBAB-CASE`, normally `<MODULE>-<FEATURE>`.
Messages use this format:

```text
[methodName] lowercase description key={} key={}
```

Example:

```java
log.info("[deactivate] role deactivated roleId={}",roleId);
log.

error("[store] object storage write failed fileId={}",fileId, exception);
```

### 8.2 Log once at the useful boundary

- Do **not** require a log statement before every `throw`.
- Domain models **MUST NOT** log.
- Expected validation, not-found, and business conflicts do not require a log
  by default.
- Log an unexpected technical failure once, at the layer with enough
  operational context.
- Do not log the same exception at every layer that rethrows it.
- Security-sensitive events may use a dedicated audit event or metric instead
  of an application warning log.
- Fragile boundaries such as external HTTP, object storage, retry, parser, and
  atomic claim operations **SHOULD** emit concise start/outcome breadcrumbs at
  `debug`, `info`, or `warn` according to operational value.

### 8.3 Levels

| Level   | Use                                                                   |
|---------|-----------------------------------------------------------------------|
| `error` | Unexpected system failure requiring operator attention; include cause |
| `warn`  | Recoverable anomaly, retry, conflict, or degraded dependency          |
| `info`  | Business-visible lifecycle event with operational value               |
| `debug` | Investigation detail disabled in production by default                |
| `trace` | Local diagnosis only                                                  |

### 8.4 Sensitive data

Never log:

- passwords, password hashes, JWTs, refresh tokens, reset tokens;
- storage credentials, secrets, authorization headers;
- full customer records, full request bodies, or file contents;
- full email addresses or phone numbers.

Mask email and phone values. A UUID may be logged in full when it is an opaque
internal identifier.

Do not use `System.out.println`, `printStackTrace`, string concatenation in log
messages, or `log.info("entity={}", entity)` when `toString()` may leak data.

---

## 9. Mapping

MapStruct is the default for mappings that cross a layer boundary and contain
multiple fields or transformation rules.

### 9.1 When a mapper is required

Use a dedicated mapper when:

- mapping between domain and JPA entity;
- mapping has multiple fields, nested values, conversions, or ignored fields;
- the same mapping is reused;
- API and application models must evolve independently.

A controller may directly create a trivial one- or two-field command when that
mapping is obvious and contains no business transformation. Do not create a
mapper only to copy one UUID.

### 9.2 Mapper conventions

```java

@Mapper(
  componentModel = MappingConstants.ComponentModel.SPRING,
  unmappedTargetPolicy = ReportingPolicy.ERROR,
  unmappedSourcePolicy = ReportingPolicy.WARN)
public interface RolePersistenceMapper {

  Role toDomain(RoleEntity entity);

  @Mapping(target = "createdAt", ignore = true)
  @Mapping(target = "lastModifiedAt", ignore = true)
  RoleEntity toNewEntity(Role domain);

  @Mapping(target = "id", ignore = true)
  @Mapping(target = "createdAt", ignore = true)
  @Mapping(target = "lastModifiedAt", ignore = true)
  void updateEntity(Role domain, @MappingTarget RoleEntity entity);
}
```

Rules:

- Use `componentModel = SPRING`.
- Use `unmappedTargetPolicy = ERROR`.
- Use `toNewEntity` for inserts and `@MappingTarget` for updates.
- Do not replace a managed JPA entity merely to apply updates.
- Inject mapper interfaces; do not instantiate them with `new`.
- Keep `lombok-mapstruct-binding` when Lombok and MapStruct process the same
  classes.

Do not use ModelMapper, BeanUtils, Dozer, Orika, reflection-based copying, or
field-by-field mapping inside an application service.

---

## 10. Configuration and secrets

Configuration is typed, validated, and fail-fast.

- Use `@ConfigurationProperties` records in `configuration`.
- Add `@Validated` and Jakarta Validation constraints for required values.
- Do not scatter `@Value` across services.
- Use the namespace `app.<module>.<key>`.
- Externalize environment-dependent and operationally tunable values. Do not
  turn ordinary constants into configuration without a reason.
- Secrets **MUST NOT** have insecure defaults.
- `.env.example` documents required variables but contains no real secret.
- Feature toggles use simple typed properties unless a real flag platform is
  explicitly required.

Example:

```java

@Validated
@ConfigurationProperties(prefix = "app.security.jwt")
public record JwtProperties(
  @NotBlank String issuer,
  @NotBlank String audience,
  @NotBlank String secret,
  @NotNull Duration accessTokenExpiration,
  @NotNull Duration refreshTokenExpiration) {
}
```

```yaml
app:
  security:
    jwt:
      secret: ${JWT_SECRET}
      issuer: ${JWT_ISSUER:file-processing}
```

---

## 11. Persistence and transactions

Persistence adapters translate between the domain and database models.

### 11.1 Entity rules

- Every JPA entity is separate from the domain model.
- Every JPA entity extends `AuditableEntity` when the common base applies.
- Every JPA entity **MUST** inherit or declare soft-delete state as
  `Instant deleteAt`, mapped to the SQL column `delete_at`.
- If `AuditableEntity` already declares `deleteAt`, do not redeclare it in the
  child entity.
- The standard Java field name is `deleteAt`; the SQL column is `delete_at`.
- Do not use `deleted`, `isDeleted`, `deletedAt`, or a boolean deletion column.
- Table and column names use `snake_case`.
- Use `@Version` for aggregates that can be concurrently updated.
- Database constraints are the final correctness boundary. Use `NOT NULL`,
  `UNIQUE`, `CHECK`, and foreign keys where appropriate.

### 11.2 Mandatory soft delete

All normal application deletes are soft deletes.

- Set `deleteAt` to the injected current `Instant`.
- Repeating delete on an already deleted object should normally be idempotent.
- Every normal business read and existence check **MUST** include
  `delete_at IS NULL`.
- Every uniqueness rule that applies only to live data **MUST** use a partial
  unique index.
- Normal repositories **MUST NOT** expose `delete`, `deleteById`, or bulk hard
  delete operations to application services.
- Physical deletion is allowed only in an explicit retention or maintenance
  job after the required retention period.
- Restore behavior, when supported, sets `deleteAt` to `null` and revalidates
  uniqueness and business invariants.

Example migration:

```sql
CREATE TABLE roles
(
  id               UUID PRIMARY KEY,
  code             VARCHAR(100) NOT NULL,
  name             VARCHAR(255) NOT NULL,
  status           VARCHAR(30)  NOT NULL,
  version          BIGINT       NOT NULL DEFAULT 0,
  created_at       TIMESTAMPTZ  NOT NULL,
  last_modified_at TIMESTAMPTZ  NOT NULL,
  delete_at        TIMESTAMPTZ NULL
);

CREATE UNIQUE INDEX roles_active_code_uidx
  ON roles (code) WHERE delete_at IS NULL;

CREATE INDEX roles_delete_at_idx
  ON roles (delete_at) WHERE delete_at IS NOT NULL;
```

### 11.3 Flyway

- `spring.jpa.hibernate.ddl-auto` **MUST** be `validate`.
- Every schema change ships as a Flyway migration.
- Migration names use
  `V{yyyyMMddHHmm}__{snake_case_description}.sql`.
- Migrations are append-only. Never edit a migration after it has been merged
  or applied outside a local disposable database.
- Fix forward with a new migration.

### 11.4 Transaction boundaries

- Put transaction boundaries on application service methods, not controllers.
- Use `@Transactional(readOnly = true)` for read-only use cases.
- Keep transactions short.
- Do not perform HTTP, email, object-storage, or message-broker calls inside a
  database transaction unless an explicit consistency requirement justifies
  it.
- Publish after commit or use an outbox when atomic database-to-message
  consistency is required.
- Map optimistic-lock conflicts to a meaningful `409 Conflict` error.
- Never retry a non-idempotent transaction blindly.

### 11.5 JPA performance

- Associations default to `LAZY`.
- Do not use `EAGER` to fix `LazyInitializationException`.
- Disable Open Session in View.
- Resolve N+1 queries explicitly with fetch joins, `EntityGraph`, projections,
  or batch fetching.
- Every list endpoint has a bounded page size and deterministic sort.
- Add indexes for verified query patterns, not every column.
- Review generated SQL for non-trivial repository changes.

---

## 12. API layer

Controllers adapt HTTP to an application use case.

- Controllers contain request validation, mapping, use-case invocation, and
  response construction only.
- Business decisions and transaction boundaries do not belong in controllers.
- Request and response contracts use DTOs, never JPA entities or domain
  aggregates.
- Wrap responses in the common `Response<T>` or `PagingResponse<T>` type.
- Public error messages resolve through i18n.
- Use a configured API prefix and version.
- Enforce bounded pagination at the request boundary.
- Unbounded list, search, and completion endpoints **MUST** follow the common
  paging convention: a request DTO extends `PagingRequest`, the controller
  parameter uses `@ValidatePaging(sortModel = Entity.class)`, the application
  query extends `PagingQuery`, the repository exposes `count(query)` and
  `search(query)`, and the controller returns `PagingResponse<T>`.
- Do not force paging onto bounded catalogs, enum lists, JWKS, `/me`, or
  current-user scoped lists unless product behavior explicitly requires it.
- Do not trust proxy forwarding headers unless the deployment config defines
  trusted proxies.

Example:

```java

@RestController
@RequestMapping("${app.api.prefix}/${app.api.version}/roles")
@RequiredArgsConstructor
public class RoleController {

  private final RoleManagementUseCase roleManagementUseCase;
  private final RoleWebMapper roleWebMapper;

  @DeleteMapping("/{roleId}")
  public Response<Void> delete(@PathVariable UUID roleId) {
    roleManagementUseCase.delete(roleId);
    return Response.noContent();
  }
}
```

Use `201 Created` with a `Location` header for resource creation when the
common response contract supports it. Use `202 Accepted` only when processing
continues asynchronously after the response.

---

## 13. OpenAPI

OpenAPI documents the actual contract; it does not replace runtime validation.

- Keep global API metadata and security schemes in one
  `OpenApiConfiguration`.
- Use Springdoc OpenAPI 3.x with Spring Boot 4.
- Public endpoints explicitly opt out of the global bearer requirement.
- Use `@Schema` for description and examples.
- Use Jakarta Validation for required fields, sizes, ranges, and formats.
- Do not publish entities, internal error causes, secrets, or implementation
  details in the schema.
- Reuse the configured bearer scheme constant instead of repeating string
  literals.

---

## 14. File processing and asynchronous work

File operations are bounded, streaming, idempotent where required, and
observable.

### 14.1 File I/O

- Stream uploads and downloads. Do not use `MultipartFile.getBytes()`,
  `Files.readAllBytes()`, or another whole-file memory load for unbounded
  content.
- Enforce maximum file size before expensive processing.
- Validate content using trusted parsers or signatures; do not trust only the
  filename extension or client-provided media type.
- Generate server-side storage names. Do not use raw user filenames as object
  keys or filesystem paths.
- Normalize and validate paths to prevent path traversal.
- Calculate checksums while streaming when integrity or deduplication requires
  them.
- Delete temporary files in a `finally` block or managed lifecycle.

### 14.2 Executors and jobs

- Do not create unbounded executors or queues.
- Size concurrency from CPU, memory, database, and downstream limits.
- Persist job state when work must survive process restarts.
- Define legal state transitions and test them completely.
- Make externally retried operations idempotent through an idempotency key,
  unique constraint, or atomic claim.
- Every external call has a timeout.
- Retry only transient failures, with bounded attempts and backoff.
- Do not retry validation failures, authorization failures, or permanent `4xx`
  responses.
- Prevent retry storms with backoff, jitter, circuit breaking, and concurrency
  limits where appropriate.

---

## 15. Security

Security rules are MUST requirements.

- Deny by default and grant the minimum required permission.
- Enforce authorization in the application/security boundary, not only by
  hiding UI actions.
- Validate ownership and tenant boundaries for every resource access.
- Do not accept roles or permissions from request payloads as trusted facts.
- JWT role and permission mapping must be explicit and tested.
- Refresh-token rotation must detect token reuse and revoke the token family.
- Passwords use an approved adaptive password hash through Spring Security.
- Never expose whether a username or email exists when that creates an account
  enumeration risk.
- File names, media types, archive entries, and parser inputs are untrusted.
- Use rate limiting for authentication and other abuse-sensitive endpoints.
- Security events require an audit trail or metric with masked identifiers.
- Never commit secrets or log authentication material.

---

## 16. Common library reuse

Reuse stable, cross-cutting components from the common library.

Typical mandatory reuse includes:

| Need                  | Shared component                               |
|-----------------------|------------------------------------------------|
| Auditable domain base | `AuditableDomain`                              |
| Auditable JPA base    | `AuditableEntity`                              |
| Response wrapper      | `Response<T>`, `PagingResponse<T>`             |
| Page result           | `PageDTO<T>`                                   |
| Paging query          | `PagingQuery`                                  |
| Current user          | `SecurityUtils`                                |
| UUID generation       | `IdUtils.nextId()`                             |
| Hashing               | `HashUtils`                                    |
| Date helpers          | `DateUtils`                                    |
| String helpers        | `StrUtils`                                     |
| Jackson configuration | `MapperFactoryUtils.jacksonMapper()`           |
| Cache                 | `CacheService`, `@CacheAction`, `@CacheUpdate` |
| AMQP publishing       | `AmqpEventPublisher`                           |

Before using a shared API, confirm its current signature in `LIBRARY.md` or its
source. Do not guess an API.

Keep module-specific helpers local. Promote code into the common library only
when at least two real consumers need the same stable abstraction. Do not bump
the common library merely to share a speculative helper.

---

## 17. Testing

Tests protect observable behavior and production risks.

### 17.1 Test levels

- Plain JUnit 5, Mockito, and AssertJ for application/domain unit tests.
- `@WebMvcTest` for MVC controller slices when useful.
- `@DataJpaTest` or a focused Testcontainers test for repository behavior.
- `@SpringBootTest` only for behavior that requires the full application
  context.
- PostgreSQL Testcontainers for SQL, migration, locking, index, and JPA
  behavior that differs from in-memory databases.

### 17.2 Required test areas

New or changed behavior **MUST** test the important paths, especially:

- authorization and ownership;
- state transitions;
- soft delete and exclusion of deleted rows;
- partial uniqueness after soft delete;
- idempotency and duplicate delivery;
- optimistic locking and atomic claims;
- file limits, malformed input, and cleanup;
- transaction rollback and after-commit behavior;
- timeout, retry classification, and fallback;
- error-code uniqueness and i18n key completeness.

Refactors with no observable behavior change do not require artificial tests.
Coverage is a diagnostic metric, not the primary acceptance criterion. Do not
write meaningless tests only to reach a number.

Test names describe the scenario:

```text
delete_throwsRoleIsActive_whenRoleIsStillActive()
delete_setsDeletedAt_whenRoleIsInactive()
findById_returnsEmpty_whenRoleWasSoftDeleted()
```

Tests must be deterministic. Do not use hidden sleeps. Inject `Clock`, control
executor completion, and wait on observable conditions.

---

## 18. Formatting, build, and Git

Spotless is mandatory.

```bash
./mvnw spotless:apply
./mvnw spotless:check
./mvnw verify
```

Before committing, run:

```bash
./mvnw spotless:apply && ./mvnw verify
```

Do not bypass hooks with `--no-verify` unless the user or repository maintainer
explicitly approves it.

Use Conventional Commits:

```text
type(scope): imperative lowercase subject
```

Common types are `feat`, `fix`, `refactor`, `perf`, `docs`, `test`, `chore`,
`style`, `ci`, and `build`.

The subject is under 72 characters, has no trailing period, and describes the
intent. The body explains why when the reason is not obvious.

Do not amend or force-push a shared branch without approval.

---

## 19. Rejected patterns

Reject or revise changes containing these patterns unless an approved exception
is documented:

1. Business logic or transactions in a controller.
2. An interface for every class or one interface per trivial method.
3. Empty packages or placeholder types created only to match a diagram.
4. Domain code importing Spring, JPA, Jackson, servlet, or HTTP types.
5. A domain exception carrying HTTP status or response-format details.
6. A custom controller advice duplicating the common exception handler.
7. Business failures represented by bare runtime exceptions.
8. Logging before every throw or logging the same exception at every layer.
9. Swallowing an exception and returning `null` or a sentinel value.
10. ModelMapper, BeanUtils, Dozer, Orika, or reflection-based field copying.
11. Replacing a managed JPA entity instead of updating it intentionally.
12. Field injection with `@Autowired`.
13. `System.out.println`, `printStackTrace`, or string-concatenated logs.
14. Logging tokens, passwords, secrets, full PII, request bodies, or file data.
15. Hard-coded user-facing messages outside i18n.
16. Error enum names without a module prefix.
17. Duplicate numeric business error codes.
18. Missing error keys in either English or Vietnamese message files.
19. Returning a JPA entity or domain aggregate from a controller.
20. Editing an applied Flyway migration.
21. `ddl-auto` set to `create`, `update`, or `create-drop` outside disposable
    local experiments.
22. A JPA entity without `deleteAt` and `delete_at`.
23. Normal application reads that do not exclude soft-deleted rows.
24. Calling repository hard-delete methods from an application service.
25. `EAGER` associations used to hide an N+1 or session-boundary problem.
26. Network or object-storage calls inside a long database transaction.
27. Unbounded page size, executor, queue, retry, or file memory load.
28. `MultipartFile.getBytes()`, `Files.readAllBytes()`, or equivalent on
    unbounded content.
29. Secrets with insecure configuration defaults.
30. New frameworks or infrastructure added without an approved requirement.

---

## 20. Pre-commit checklist

- [ ] Relevant source, tests, `AGENTS.md`, and `LIBRARY.md` were inspected.
- [ ] The change uses the smallest design that preserves real boundaries.
- [ ] Domain code has no Spring, JPA, Jackson, servlet, or HTTP dependency.
- [ ] New interfaces represent real inbound or outbound boundaries.
- [ ] Every new entity inherits or declares `deleteAt` mapped to `delete_at`.
- [ ] Business reads use `delete_at IS NULL` unless explicitly reading the trash.
- [ ] Live-data uniqueness uses an appropriate partial unique index.
- [ ] Schema changes use a new append-only Flyway migration.
- [ ] Transaction boundaries are short and on application services.
- [ ] External calls have timeouts and are outside DB transactions unless
  explicitly justified.
- [ ] JPA list queries were checked for N+1, bounded pagination, and stable sort.
- [ ] Error enum names start with the module prefix.
- [ ] Numeric business error codes are unique.
- [ ] English and Vietnamese i18n keys exactly match each error enum name.
- [ ] Errors are logged once at the useful boundary, with no sensitive data.
- [ ] Non-trivial boundary mappings use MapStruct; updates use `@MappingTarget`.
- [ ] Secrets have no insecure defaults.
- [ ] File and async operations are bounded, streaming, and idempotent where
  required.
- [ ] New behavior has risk-appropriate deterministic tests.
- [ ] `./mvnw spotless:apply && ./mvnw verify` passes.
- [ ] The commit follows Conventional Commits.
