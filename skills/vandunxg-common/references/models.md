### common-models

**Purpose.** `common-models` is the dependency-free "contract" layer shared by every Vandunxg microservice: the `ResponseError`/`ResponseException` family for raising structured, HTTP-status-mapped errors; the `Response<T>`/`ErrorResponse<T>`/`PagingResponse<T>` envelope types every controller should return; request DTOs for common query shapes (paging, find-by-ids/codes/names); a `EntityMapper<D,E>` contract for domain↔entity mapping; Spring-Security `Authentication`/auth-snapshot types (`UserAuthentication`/`UserAuthority`); and the `Auditable*` base classes for created/modified metadata. It has no dependency on any other `common-*` module (foundation layer alongside `common-utils`) and every package is `@NullMarked` (JSpecify). Reach for this module before hand-rolling a response wrapper, a paging DTO, a custom exception hierarchy, or an auditing base class — it almost certainly already exists here.

#### Error model: `ResponseError` + enums

`com.vandunxg.common.models.error.ResponseError` — interface every error enum implements:

```java
public interface ResponseError {
  String getName();          // enum constant name, used as i18n message key

  String getMessage();       // default English message, may contain {0},{1}... MessageFormat placeholders

  int getStatus();           // HTTP status to respond with

  default Integer getCode() {
    return 0;
  }  // numeric error code
}
```

| Enum (`com.vandunxg.common.models.error.*`) | HTTP status | Notable constants (code, message)                                                                                                                                                                                                                                                                                                                                                                                                                             |
|---------------------------------------------|-------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `AuthenticationError`                       | 401         | `UNKNOWN`(40100001), `UNAUTHORISED`(40100002), `FORBIDDEN_ACCESS_TOKEN`(40100003), `FORBIDDEN_REFRESH_TOKEN`(40100004), `INVALID_REFRESH_TOKEN`(40100005), `VALIDATE_EXPIRATION_TIME`(40100006), `VALIDATE_TOKEN_ID`(40100007), `VALIDATE_ISSUER`(40100008), `ONLY_CLIENT_ACCESS_RESOURCE`(40100009), `INVALID_API_TOKEN`(40100010), `INVALID_JWT_SIGNATURE_REFRESH_TOKEN`(40100011), `EXPIRED_REFRESH_TOKEN`(40100012), `REFRESH_TOKEN_WAS_REVOKE`(40100013) |
| `AuthorizationError`                        | 403         | `ACCESS_DENIED`(40300001), `NOT_SUPPORTED_AUTHENTICATION`(40300002)                                                                                                                                                                                                                                                                                                                                                                                           |
| `BadRequestError`                           | 400         | `INVALID_INPUT`(40000001, "Invalid input : {0}"), `INVALID_ACCEPT_LANGUAGE`(40000002), `MISSING_PATH_VARIABLE`(40000003), `PATH_INVALID`(40000004), `UNDEFINED`(40000005), `FILE_SIZE_EXCEEDED`(40000006), `RECORD_IS_BEING_UPDATED`(4000007), `MISSING_HEADER_VARIABLE`(4000008, "Missing header variable: {0}"), `BAD_REQUEST_ERROR`(4000009)                                                                                                               |
| `NotFoundError`                             | 404         | `NOT_FOUND`(40400001), `USER_NOT_FOUND`(40400002, "User not found: {0}")                                                                                                                                                                                                                                                                                                                                                                                      |
| `InternalServerError`                       | 500         | `INTERNAL_SERVER_ERROR`(50000001), `DATA_ACCESS_EXCEPTION`(50000002)                                                                                                                                                                                                                                                                                                                                                                                          |
| `ServiceUnavailableError`                   | 503         | `SERVICE_UNAVAILABLE_ERROR`(50300001)                                                                                                                                                                                                                                                                                                                                                                                                                         |

Note: `common-client` defines its own lookalike `BadRequestError`/`NotFoundError` enums (`com.vandunxg.common.client.supports.exception`) that also implement this same `ResponseError` — the pattern is meant to be reused per-service/per-module, not restricted to this module's enums.

`com.vandunxg.common.models.enums.ErrorCodeClient` — plain enum `SUCCESS, FAIL, TIMEOUT`; its `.name()` is what `Response`/`ErrorResponse` store in their `status` field.

#### Throwing errors: 4 exception types, all in `com.vandunxg.common.models.exception`

All four wrap a `ResponseError` + optional `Object... params` (applied via `java.text.MessageFormat` against the message string) and share the same constructor shapes:

```java
XxxException(ResponseError error)

XxxException(ResponseError error, Object... params)

XxxException(String message, ResponseError error)

XxxException(String message, ResponseError error, Object... params)

XxxException(String message, Throwable cause, ResponseError error, Object... params)
```

`getError()` returns the `ResponseError`; `getParams()` returns a defensive copy of the params array.

- **`ResponseException`** — the default choice. `common-web`'s `ExceptionHandleAdvice` catches it, logs the full stack trace, and re-resolves the message through i18n (`localeStringService.getMessage(error.getName(), error.getMessage(), params)`) before building an `ErrorResponse`.
  ```java
  throw new ResponseException(NotFoundError.USER_NOT_FOUND, userId);
  throw new ResponseException(BadRequestError.INVALID_INPUT, "email");
  ```
- **`ShortResponseException`** — identical behavior/constructors to `ResponseException`, but the advice logs it **without** the stack trace — use for expected/anticipated business-rule failures you don't want cluttering logs with traces.
- **`CustomResponseException`** — same constructors, but the advice does **not** re-localize: it emits `e.getMessage()` verbatim (the already-`MessageFormat`-ted string). Use when you want the exact message you built, bypassing i18n key lookup. (Per `CHANGELOG.md`, this is a structural duplicate of `ResponseException` retained for backward compatibility, slated for eventual `@Deprecated`.)
- **`ForwardInnerAlertException`** — different shape entirely: wraps an already-built `ErrorResponse<Void>` (single constructor `ForwardInnerAlertException(ErrorResponse<Void> response)`, `getResponse()` accessor). **Not** handled by `ExceptionHandleAdvice` (no `@ExceptionHandler` for it) — it's a carrier for re-throwing a pre-built error payload as-is (e.g. an error body deserialized from a downstream/proxied call) and must be caught by the consumer's own filter/interceptor.

#### Error/response DTOs (`dto/error`, `dto/response`, `dto`)

- **`dto.error.ErrorResponse<T>`** extends `Response<T>` (below), adds `String error` (the failing `ResponseError.getName()`). Constructor `ErrorResponse(int code, String message, T data, String error)` forces `success=false` and `status=FAIL`. Static builder `ErrorResponse.<T>builder().code(int).message(String).data(T).error(String).build()`.
- **`dto.error.InvalidInputResponse`** extends `ErrorResponse<Void>`, adds `Set<FieldErrorResponse> errors`. Used for validation-failure responses (400 with per-field detail).
- **`dto.error.FieldErrorResponse`** — `field`, `objectName`, `message` (all `String`), builder-style.
- **`dto.response.Response<T>`** — the base envelope. Fields: `data`(`@Nullable T`), `success`(boolean, default true), `code`(int, default 200), `message`(`@Nullable String`), `timestamp`(long, epoch millis, auto-set), `status`(String, `ErrorCodeClient` name), `exception`(`@JsonIgnore RuntimeException`, if set `getData()`/`isSuccess()` **rethrow** it). Static factories: `of(T res)`, `ok()`, `fail(RuntimeException)`, `fail(String message)`, `fail(String message, RuntimeException)`, `fail()`.
- **`dto.response.PagingResponse<T>`** extends `Response<List<T>>`, adds nested `PageableResponse{pageIndex,pageSize,total}`. Static `PagingResponse.of(List<T>, pageIndex, pageSize, total)`, `PagingResponse.of(PageDTO<T>)`, `PagingResponse.failPaging(RuntimeException)`.
- **`dto.response.BaseResponse`** — audit-only response base (see Auditable section) with builder.
- **`dto.PageDTO<T>`** — internal (pre-HTTP) paging container, nested `PageableDTO{pageIndex,pageSize,total}`. Constructor `PageDTO(Page<U> pageInput, Function<List<U>,List<T>> mapper)` bridges Spring Data `Page`. Static `PageDTO.of(data,pageIndex,pageSize,total)`, `PageDTO.empty()`, `PageDTO.empty(PagingRequest request)`.

#### Request DTOs (`dto/request`)

All extend `com.vandunxg.common.models.dto.request.Request` (abstract, `Serializable`).

- **`PagingRequest`** — `keyword`(`@Nullable`), `pageIndex`(`@Min(1)`, default `1`), `pageSize`(`@Min(1) @Max(500)`, default `30`), `sortBy`(`@Nullable`, format `"field.asc"`/`"field.desc"`, comma-separated for multi-sort). Has full constructor + `PagingRequest.builder()`.
- **`ExportRequest`** extends `PagingRequest` — widens bounds for bulk export: `pageIndex` `@Max(1000)`, `pageSize` `@Max(50000)`.
- **`SearchByKeywordRequest`** extends `PagingRequest` — trivial subtype, constructor sets only `keyword`.
- **`FindByIdsRequest`** — `List<UUID> ids` (`@NotEmpty`), constructor + builder.
- **`IdsRequest`** — same shape as `FindByIdsRequest` but setter-only, no builder — functional duplicate; prefer `FindByIdsRequest`.
- **`FindByCodesRequest`** — `List<String> codes` (`@NotEmpty`).
- **`FindByNamesRequest`** — `List<String> names` (`@NotEmpty`).
- **`FindByUsernamesRequest`** — `List<String> usernames` (`@NotEmpty`).

#### Validation annotations (`validator`)

- **`@ValidateUUID`** (`FIELD, PARAMETER, CONSTRUCTOR, METHOD, TYPE_USE`) — validated by `IdValidator implements ConstraintValidator<ValidateUUID,String>`: `null` → valid; blank → invalid; otherwise `UUID.fromString(value)`, any exception → invalid.
  ```java
  public class SomeRequest { @ValidateUUID private String id; }
  ```
- **`@ValidatePaging`** (`PARAMETER` only, attribute `sortModel: Class<?>` default `Object.class`) — validated by `PagingValidator implements ConstraintValidator<ValidatePaging,PagingRequest>`. If `sortModel` is left `Object.class`, always passes. Otherwise reflects over `sortModel`'s `@jakarta.persistence.Column`-annotated fields, builds an allow-list of `fieldName`/`fieldName.asc`/`fieldName.desc`, and rejects any `sortBy` token not on that list (attaches a field error to `"sortBy"`).
  ```java
  public ResponseEntity<PagingResponse<UserDTO>> list(
      @Valid @ValidatePaging(sortModel = UserEntity.class) PagingRequest request) { ... }
  ```
- **`ValidateConstraint`** — not an annotation; a plain interface holding two nested constant containers to reuse instead of hardcoding literals in `@Size`/`@Pattern`:
  - `.LENGTH` (ints): `CODE_MAX_LENGTH=50`, `NAME_MAX_LENGTH=100`, `TITLE_MAX_LENGTH=200`, `DESC_MAX_LENGTH=1000`, `ID_MAX_LENGTH=36`, `PASSWORD_MIN/MAX_LENGTH=3/50`, `EMAIL_MAX_LENGTH=50`, `PHONE_MAX_LENGTH=20`, and more.
  - `.FORMAT` (regex Strings): `PHONE_NUMBER_PATTERN`, `EMAIL_PATTERN`, `CODE_PATTERN`, `WEBSITE`, `ACCOUNT_NUMBER`, `PASSWORD_REGEX`, and more.
  ```java
  @Size(max = ValidateConstraint.LENGTH.NAME_MAX_LENGTH) private String name;
  @Pattern(regexp = ValidateConstraint.FORMAT.EMAIL_PATTERN) private String email;
  ```
- **`ValidatorUtils`** — static helper `createErrorField(ConstraintValidatorContext, field, message, disableDefaultConstraint)` for attaching a field-scoped violation from your own custom validators.

#### Mapping contracts (`mapper`)

- **`EntityMapper<D,E>`** — plain interface, implement per domain/entity pair (by hand or MapStruct):
  ```java
  public interface EntityMapper<D, E> {
    D toDomain(E entity); List<D> toDomain(List<E> entities);
    E toEntity(D domain); List<E> toEntity(List<D> domains);
  }
  ```
  `common-web`'s `AbstractDomainRepository<D,E,I>` takes one of these + a `JpaRepository<E,I>` to drive `save`/`findById`/`findAllByIds`.
- **`mapper.utils.PageableMapperUtils.toPageable(PagingRequest)`** → Spring Data `Pageable`. Parses `sortBy` as comma-separated `field.direction` tokens; defaults to `Sort.Order.desc("createdAt")` if nothing usable. Converts 1-based `pageIndex` to Spring Data's 0-based `PageRequest`.

#### Auth model (top-level package `com.vandunxg.common.models`)

- **`UserAuthentication`** extends `UsernamePasswordAuthenticationToken` — i.e. it *is* a Spring Security `Authentication`. Adds `UUID userId`, `String token` (raw access token), `List<String> grantedPermissions` (derived from the `GrantedAuthority` collection at construction). Getters: `getUserId()`, `getToken()`, `getGrantedPermissions()`. This is the `Authentication` type `common-web`'s `SecurityUtils`/`RegexPermissionEvaluator`/`ForbiddenTokenFilter` all expect — a consumer's JWT converter must produce this type for those utilities to work.
- **`UserAuthority`** — plain POJO (NOT a `GrantedAuthority`) snapshotting a user's auth state for caching/propagation: `List<String> grantedPermissions`, `Instant lastAuthChangeAt`, `UUID userId`, `String username`, `String role`. Full constructor + builder.

#### Auditing base classes

Three parallel field sets (`createdBy`, `createdAt`, `lastModifiedBy`, `lastModifiedAt`) for three layers — pick the one matching where your class lives:

- **`domain.AuditableDomain`** — plain `Serializable` object, all fields `@Nullable`, builder. For internal business/domain objects (not JPA entities, not API DTOs).
- **`entities.AuditableEntity`** — JPA `@MappedSuperclass` + `@EntityListeners(AuditingEntityListener.class)`, Spring Data auditing annotations (`@CreatedBy`→`created_by`, `@CreatedDate`→`created_at`, `@LastModifiedBy`→`last_modified_by`, `@LastModifiedDate`→`last_modified_at`). For `@Entity` classes; requires the consumer to `@EnableJpaAuditing` + provide an `AuditorAware<String>` bean (`common-web`'s `SpringSecurityAuditorAware` is the intended one).
- **`dto.AuditableDTO`** — plain API-facing DTO analog (`@Nullable` fields, getters/setters), no builder.
- **`dto.response.BaseResponse`** — same 4 fields with a builder, for REST *response* DTOs specifically.

---
