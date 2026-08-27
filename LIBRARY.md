# vandunxg-common — Library Contract Reference (for AI coding agents)

> **Purpose of this file:** this is a machine-oriented API contract for the `vandunxg-common` Spring Boot library. It exists so an AI coding agent working in a *consumer* repository (a Vandunxg microservice that depends on this library) can check here **before** implementing utility code, DTOs, error handling, caching, messaging, email, HTTP client integrations, or web/security plumbing — because it likely already exists in this library. Prefer reusing what's documented here over reimplementing it. This file is generated from the library's source via CodeGraph exploration; verify against the actual dependency version in use if something looks off (check `CHANGELOG.md` in this repo for behavior that changed between versions).

## Quick facts

|                 |                                                                                      |
|-----------------|--------------------------------------------------------------------------------------|
| groupId         | `com.vandunxg.common`                                                                |
| Current version | `3.0.1`                                                                              |
| Java            | 21+                                                                                  |
| Spring Boot     | 4.0+ (built against `4.0.5`)                                                         |
| Packaging       | Maven multi-module, one artifact per module (no umbrella "all-in-one" artifact)      |
| Registry        | GitLab Maven registry — `https://gitlab.com/api/v4/projects/81036445/packages/maven` |
| License         | MIT                                                                                  |
| Source          | `modules/<name>/src/main/java/com/vandunxg/common/<name>/...`                        |

**Migration note:** 3.0.0 removes foundation APIs and transitive dependencies. See the [foundation migration guide](docs/migration/3.0.0-foundation.md) before upgrading.

## Installation

```xml

<repositories>
  <repository>
    <id>gitlab</id>
    <url>https://gitlab.com/api/v4/projects/81036445/packages/maven</url>
  </repository>
</repositories>

<dependency>
<groupId>com.vandunxg.common</groupId>
<artifactId>common-web</artifactId> <!-- or whichever module(s) you need -->
<version>3.0.1</version>
</dependency>
```

If the registry requires auth, add a `<server id="gitlab">` block with a `Private-Token` header to `~/.m2/settings.xml` — see this repo's `README.md` for the exact snippet.

## Module map

Pick modules by what you need — each is a separate Maven artifact, not a monolith. Depend only on what you use.

| Artifact             | Depends on (internal)                                           | One-liner                                                                                                                              |
|----------------------|-----------------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------|
| `common-utils`       | — (foundation)                                                  | String/date/crypto/serialization/Vietnamese-locale utilities. No Spring dependency beyond `spring-core`.                               |
| `common-models`      | — (foundation)                                                  | Shared response/error/DTO/validation "contract" types used across all services' APIs.                                                  |
| `common-persistence` | `common-models`                                                 | JPA hardening: SQL-injection-safe statement inspector, sequence generator, SQL helpers.                                                |
| `common-cache`       | `common-utils`                                                  | Redis caching: standard Spring cache abstraction + custom AOP annotations for partial collection/map cache updates.                    |
| `common-amqp`        | —                                                               | RabbitMQ async event publishing with a consistent envelope.                                                                            |
| `common-email`       | —                                                               | SMTP send (HTML/attachments/inline) + IMAP scan-and-extract.                                                                           |
| `common-client`      | `common-utils`, `common-models`, `common-cache`, `common-email` | Pre-built HTTP integrations: ACB Bank, CloudFlare DNS, Mikrotik, firewall/proxy providers.                                             |
| `common-web`         | `common-utils`, `common-models`, `common-persistence`           | Spring Web/Security layer: JWT/OAuth2 resource-server setup, global exception handling, custom Jackson (de)serializers, i18n, Swagger. |

**Layering:** `common-utils`/`common-models` are the foundation everything else builds on. `common-persistence` and `common-cache` are thin, focused add-ons. `common-amqp`/`common-email` are independent side capabilities. `common-client` and `common-web` are the two "batteries-included" layers that pull several of the others together — most services will end up depending on `common-web` (for the API layer) and optionally `common-client`/`common-amqp`/`common-email` for specific integrations.

## Cross-cutting conventions (apply to every module)

- **`*Adapter` names below are this library's own API, not a project naming
  convention.** `common-client` ships classes such as `BankAdapter`,
  `CloudFlareAdapter`, and `MikrotikPartnerAdapter`; this document records them
  verbatim because that is what the published artifact exposes. The
  `file_processing` service itself is **Pragmatic Modular DDD** and **MUST NOT**
  use `Adapter` or `Port` as a naming suffix — see [`RULE.md` §4.6](./RULE.md).
  Likewise, "port" elsewhere in this document means a TCP/SMTP port, never a
  Hexagonal port.
- **Null-safety**: every package is `@NullMarked` (JSpecify) — parameters/returns are non-null by default; explicit `@Nullable` marks the exceptions. Trust the annotations; don't add defensive null checks the type system already rules out.
- **Auto-configuration, not component-scanning — except in `common-web`**: since `2.0.0`, `common-persistence`, `common-cache`, `common-amqp`, `common-email`, and `common-client` register every bean through exactly one `@AutoConfiguration` class per module (listed in each module's `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`), with every bean method `@ConditionalOnMissingBean` — **define your own bean of the same type in your service to override/replace any library-provided bean**, no exclusion/profile juggling needed. **`common-web` is the exception**: only `TokenCacheAutoConfiguration` is a real auto-configuration there; everything else (`WebSecurityConfig`, `JacksonConfiguration`, `ExceptionHandleAdvice`, the security filters, etc.) still carries `@Component`/`@Configuration`/`@ControllerAdvice` and needs the consumer's `@SpringBootApplication` to scan (or `@Import`) package `com.vandunxg.common.web` — see the `common-web` section below for the
  full checklist.
- **Config properties are pure data holders** — no `@Bean` methods live on a `@ConfigurationProperties` class in this library.
- Auto-configuration classes registered today, one per module that has any: `common-persistence.JpaDatabaseAutoConfiguration`, `common-cache.RedisCacheAutoConfiguration`, `common-amqp.config.AmqpAutoConfiguration`, `common-email.config.EmailAutoConfiguration`, `common-client.config.ClientAutoConfiguration`, `common-web.config.TokenCacheAutoConfiguration`. (`common-utils`/`common-models` have no Spring beans at all — pure library code.)

---

### common-utils

Foundation, dependency-free layer of `vandunxg-common` (artifact `common-utils`, package `com.vandunxg.common.utils`). Reach for it whenever a consumer service needs: date/time formatting & parsing (VN business calendar semantics), string manipulation/masking, ID/UUID handling, IP validation, hashing/HMAC, RSA encryption, compact binary serialization, Vietnamese number-to-words/currency formatting, or generic Jackson `ObjectMapper` construction — before writing any of that logic by hand. It has no dependency on any other `common-*` module (only `commons-lang3`, `tools.jackson.core:jackson-databind`, `com.fasterxml.jackson.core:jackson-annotations`, `org.jspecify:jspecify`, `io.protostuff:protostuff-api`, `protostuff-core/runtime`, `spring-core`). Every class is a non-instantiable `final` utility class (private constructor throwing `IllegalStateException`) unless noted otherwise.

#### `CompressionUtils`

Deflate/Inflate byte-array (de)compression; backs `SerializationUtils`.

- `compress(byte[] data) → byte[]` (throws `IOException`)
- `decompress(byte[] data) → byte[]` (throws `IOException, DataFormatException`)

#### `Constants`

Grab-bag of app/auth-context string constants (not a generic string pool — see `StringPool` for that) plus valid image-extension whitelist.
Representative: `ANONYMOUS_ACCOUNT="anonymous"`, `HTTP="http://"`, `HTTPS="https://"`, `API_TOKEN="Api-token"`, `CLIENT_ID="client-id"`, `CLIENT_SECRET="client-secret"`, `REFRESH_TOKEN`, `REMOTE_IP`.

- `getValidExtensions() → List<String>` — `["bmp","jpg","png","jpeg"]`, used by `FileUtils.validateExtension`.

#### `CurrencyUtils`

BigDecimal rounding/formatting + Vietnamese amount-in-words.

- `LOCALE_VN`/`LOCALE_EN` public constants (`vi-VN`, `Locale.US`)
- `roundingAndFormat(BigDecimal) → String` — HALF_UP to scale 0, EN-formatted
- `formatVN(BigDecimal) → String`, `formatEN(BigDecimal) → String`
- `toVietnameseWords(long|BigDecimal) → String` — delegates to `NumToVNWord`
- `readNum(String numberText) → List<String>` — splits Vietnamese words of an integer string (must match `^-?\d+$`, else `IllegalArgumentException`)

#### `DataUtils`

Reflection-based object→map flattening and token-substitution templating.

- `getValueOrDefault(T value, T defaultValue) → T`
- `parseDataToContent(String content, T templateData, String characterBefore, String characterAfter) → String` — replaces `characterBefore + key + characterAfter` tokens using bean-property + raw-field values of `templateData` (or a `Map` directly); unmatched tokens left as-is.

#### `DateUtils`

Instant-based formatting, parsing, and Vietnamese fiscal-period math. Formatting and parsing use the **hardcoded zone `Asia/Ho_Chi_Minh`** (`DEFAULT_ZONE_ID`, not system default/UTC) unless an explicit `ZoneId` is supplied; `formatHttpDate(Instant)` produces UTC/RFC 1123 output. Get the default zone via `getDefaultZoneId()`.
Pattern constants: `NORM_DATE_PATTERN="yyyy-MM-dd"`, `NORM_3_DATE_PATTERN="dd/MM/yyyy"`, `NORM_DATETIME_PATTERN="yyyy-MM-dd HH:mm:ss"`, `HTTP_DATETIME_PATTERN`.

- `now() → String`, `today() → String`
- `formatDateTime(Instant) / formatDate(Instant) / format(Instant, pattern) / format(Instant, pattern, ZoneId) → String`
- `formatHttpDate(Instant) → String` (UTC/RFC 1123)
- `formatLocalDate(LocalDate) / formatLocalDateTime(LocalDateTime) → String`
- `parseToLocalDate(String) → LocalDate` (throws on bad format) / `tryParseToLocalDate(String) → Optional<LocalDate>` (tries 5 known patterns)
- `tryParse(String) / tryParse(String, pattern) → Optional<Instant>`
- `parseStartOfDay(String) / parseEndOfDay(String) → Optional<Instant>`
- `getTimeStart(String) / getTimeFinish(String) → Instant`
- `getFirstDayOfCurrentMonth/Week() / getLastDayOfCurrentMonth() → Instant`
- `getFirstDayOfPreviousMonth() / getLastDayOfPreviousMonth() → LocalDate` and `getFirstDayOfPreviousMonthIns() / getLastDayOfPreviousMonthIns() → Instant`
- `getDayCount(start, end) → int` (inclusive; 0 if unparsable or end<start)
- `getDatesInRange(LocalDate, LocalDate) → List<LocalDate>`
- `checkIntersectionBetweenTimes(effectiveDate, expirationDate, effectiveDateNeedCheck, expirationDateNeedCheck) → boolean`
- `isDatetimeWithPattern(value[, pattern]) → boolean`
- `firstOfPeriod(ReportingPeriodType, year) / lastOfPeriod(...) → LocalDate` and `getMonthByPeriod(ReportingPeriodType) → List<Month>` — VN fiscal quarter/half/year math
- `firstOfMonth/lastOfMonth(year, Month) / firstOfYear/lastOfYear(year) → LocalDate`
- `spendNt(preTime) / spendMs(preTime) → long`

#### `FileUtils`

Filename sanitization/parsing and simple content-byte generation.

- `newExternalId() → String` (UUID)
- `getExtension(String fileName) → Optional<String>` (lowercased, no leading dot)
- `getBaseName(String fileName) → Optional<String>`
- `sanitizeFileName(String) → String` — strips `/`, `\`, NUL
- `validateExtension(String fileName) → boolean` — against `Constants.getValidExtensions()`
- `generateTxtContent(List<String>) / generateCsvContent(List<String>) → byte[]` — UTF-8, `\n`-joined (both are identical impls today)

#### `HashUtils`

SHA-256 digest and HMAC-SHA256.

- `sha256(byte[] | Path) → String` (hex)
- `sha256Random() → String` — SHA-256 of a random UUID
- `hmacSha256(String data, String secretKey) → String` (hex)

#### `IdUtils`

UUID generation/validation.

- `nextId() → UUID`
- `isUuid(@Nullable String) → boolean` — strict UUID v1-8 regex (variant `8/9/a/b` only)
- `parseUuid(@Nullable String) → Optional<UUID>`

#### `IpUtils` (common-utils)

IPv4/IPv6 validation and reachability/port checks. **Name collision warning**: a *different* `com.vandunxg.common.web.support.IpUtils` exists in `common-web` (servlet-request IP extraction) — see that section, and do not confuse the two.

- `isIpV4(String) / isIpV6(String) → boolean`
- `isReachable(String ip, int timeoutMillis) → boolean`
- `isPortOpen(String ip, int port, int timeoutMillis) → boolean`

#### `MapperFactoryUtils`

Central factory for Jackson `ObjectMapper`s using the **Jackson 3 package namespace** (`tools.jackson.databind.*`, not classic `com.fasterxml.jackson.databind.*` — annotations like `@JsonInclude` are still `com.fasterxml.jackson.annotation`).

- `jacksonMapper() → ObjectMapper` — `NON_NULL` inclusion, unknown properties ignored
- `jacksonMapper(boolean failOnUnknownProperties) → ObjectMapper`
- `jacksonMapper(Consumer<ObjectMapper> customizer) → ObjectMapper`

#### `NumberUtils`

- `calculatePercentageDifference(double current, double baseline) → OptionalDouble` — empty if `baseline==0` (and both non-zero); `0` if both zero
- `processNumber(BigDecimal) → String` — null-safe "0", else 2-decimal HALF_UP formatted
- `formatBigDecimal(BigDecimal) → String` — pattern `#,##0.##`, `Locale.ROOT` symbols

#### `NumToVNWord`

Vietnamese number-to-words converter (long only — no decimals).

- `toWords(long value) → String` — handles negative (`"âm ..."`) and zero (`"không"`)
- `num2String(long) → String` — alias for `toWords`

#### `PredicateUtils`

- `distinctByKey(Function<T,?> keyExtractor) → Predicate<T>` — stateful, backed by a fresh `ConcurrentHashMap` key-set; typical `stream().filter(distinctByKey(...))` dedup idiom
- `distinctByKey(Function<T,?> keyExtractor, Set<Object> seen) → Predicate<T>` — caller-supplied (shared/reusable) seen-set overload

#### `RandomUtils`

Secure random string/code/id generation (`SecureRandom`-backed for token/secret methods; `ThreadLocalRandom` for bounded ints).

- `generateSecret() → String` (100 chars, alphanumeric), `generatePassword()` (20), `generateSalt()` (10)
- `generateActivationKey() / generateResetKey() → String` (20-digit numeric)
- `generateRandom(int bound) → int`, `randomInRange(startInclusive, endExclusive) → int`, `randomQuantityInRange(start, end, quantity) → List<Integer>`
- `uniqueExternalID() → Long` — monotonically increasing, CAS-based on `System.currentTimeMillis()`
- `generateCode([length]) → String` (default 10, uppercase alphanumeric), `generateWithPrefix(prefix, length) → String` (letters only)

#### `ReflectionUtils`

Bean/field introspection helpers (distinct from `DataUtils`, which does object→map).

- `hasProperty(Class<?> type, String name) → boolean` — checks declared fields AND bean getter/setter properties
- `getFieldValue(Object target, String name) → Optional<Object>` — reflective, sets field accessible
- `clone(T extends Serializable) → T` — delegates to Apache Commons `SerializationUtils.clone` (Java serialization, requires `Serializable` graph)
- `isNullInputObject(Object) → boolean` / `isAllFieldsNull(Object, Set<String> ignoredFieldNames) → boolean` — skips `static` fields and a default-ignore set (`serialVersionUID, pageIndex, pageSize, orderByColumn, orderByType, length`)

#### `ReportingPeriodType` (enum)

Vietnamese fiscal reporting periods: `FIRST_QUARTER, SECOND_QUARTER, THIRD_QUARTER, FOURTH_QUARTER, FIRST_HALF, SECOND_HALF, YEAR`.

#### `RsaProvider`

RSA-OAEP asymmetric crypto with block chunking for arbitrary-length payloads.

- Fixed transform `RSA/ECB/OAEPWithSHA-256AndMGF1Padding`; **minimum key size enforced at 2048 bits**.
- `generateKeyPair([keySize]) → KeyPair`
- `toBase64(PublicKey|PrivateKey) → String` — **raw X.509/PKCS8 DER, base64-encoded, no PEM headers/newlines** — strip PEM headers before feeding into `fromPublicKey`/`fromPrivateKey`.
- `fromPublicKey(String base64) → PublicKey` / `fromPrivateKey(String base64) → PrivateKey` — throws `RsaOperationException` on invalid input
- `encryptToBase64(String plainText, PublicKey) → String` / `decryptFromBase64(String cipherTextBase64, PrivateKey) → String`
- `encrypt(byte[], PublicKey) / decrypt(byte[], PrivateKey) → byte[]` — auto-chunks by key size
- `generatePublicKeyFromPrivateKey(String base64PrivateKey | PrivateKey) → PublicKey|String` — requires an `RSAPrivateCrtKey`

#### `SerializationUtils`

Compact binary (de)serialization via ProtoStuff + Deflate compression — **not** a substitute for Jackson JSON; produces a proprietary compressed binary blob.

- `serializeToByte(T object) → byte[]` — schema derived automatically via `RuntimeSchema.createFrom` (no manual schema registration needed), Deflate-compressed.
- `deserializeFromByte(byte[] data, Class<T> type) → T` — instantiates via Objenesis (bypasses constructors), decompresses, merges via ProtoStuff.
- Gotcha: byte layout depends on reflected field order/types at serialize time — don't persist across incompatible class-shape changes.

#### `StreamUtils`

- `getBatches(List<T> collection, int batchSize) → List<List<T>>` — partitions into sub-lists (`batchSize` must be > 0)

#### `StringPool`

Generic string/char constant pool (SQL fragments, brackets, whitespace, encodings, common file extensions) plus an ASCII lookup table. **Overlaps with `StrUtils`'s own constants** (`DOT/SLASH/STAR`, same values, separate declarations).

- `ascii(int code) → String` (0-127 only, else `IllegalArgumentException`)
- `asciiTableCopy() → String[]`

#### `StrUtils`

Primary string-manipulation utility (highest fan-in in the module).

- `isBlank(@Nullable String|Object) / isNotBlank / isEmpty(@Nullable String) → boolean`
- `equalsNotEmpty(left, right) → boolean`
- `getGeneralField(getOrSetMethodName) → String` — strips `get`/`set` prefix, lower-cases first char
- `genSetter(fieldName) / genGetter(fieldName) → String`
- `upperFirst(String) / lowerFirst(String) → String`
- `removePrefix/removePrefixIgnoreCase/removeSuffix/removeSuffixIgnoreCase(value, affix) → String`
- `split(String value, char separator[, limit]) → List<String>`; `split(String value, String delimiter) → String[]`
- `repeat(char, count) → String`
- `convertCharset(value, sourceCharset, destinationCharset) → String`
- `format(String template, Object... values) → String` — `{}` placeholder substitution (SLF4J-style, not `String.format`)
- `compareText(key, line, scoreRate) → boolean` / `levenshteinDistance(left, right) → int`
- `containText(left, right) → boolean` — bidirectional case-insensitive substring check
- `foldingAscii(String) → String` — NFD-normalize, strip diacritics, `đ→d`, collapse whitespace
- `removeAccent(String) → String` — delegates to `VNCharacterUtils`
- `emailFormat(String) / phoneNumberFormat(String) / addressFormat(String) → String` — PII masking helpers
- `isNumeric(String) → boolean`
- `generateCodeFromId(long id, int padLeft) → String` — base-36 uppercase
- `generateNewCodeFromFixedSize(code, Instant createdAt, maxSize) / generateCodeFromFixedSize(code, randomString, maxSize) → String`
- `hideHalfOfStringWithStar(String) → String`
- `mixCharacter(String word[, Random]) → String` — shuffles characters (not encryption)
- `genSlug(String) → String` — NFD strip-accent, non-word chars → space, collapse → `-`, lowercase

#### `VNCharacterUtils`

Vietnamese diacritic removal (NFD normalize + strip combining marks + explicit `đ/Đ → d/D`).

- `removeAccent(String) → String` (returns `null` for `null` input)
- `removeAccent(char) → char`

#### Notable gotchas (common-utils)

- `DateUtils` hardcodes `Asia/Ho_Chi_Minh` as its default zone — do not assume UTC/JVM-default.
- Two same-named `IpUtils` classes exist (`common-utils` pure validation vs `common-web.support` servlet-aware) — check the import.
- `StringPool` and `StrUtils` both declare overlapping constants (`DOT`, `SLASH`, `STAR`) — duplication, not a bug.
- `RsaProvider` requires raw base64 DER (not PEM); enforces ≥2048-bit keys.
- `SerializationUtils` output is a proprietary Deflate+ProtoStuff blob, not portable JSON/protobuf, and uses Objenesis (no constructor call on deserialize).
- `MapperFactoryUtils` returns Jackson **3.x** (`tools.jackson.databind`) types, not classic `com.fasterxml.jackson.databind.ObjectMapper`.
- `NumToVNWord` only supports `long` (no fractional amounts — round first, or use `CurrencyUtils.toVietnameseWords(BigDecimal)`).
- All utility classes are `final` with a private no-arg constructor that throws — never attempt instantiation/subclassing.

---

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

### common-persistence

JPA hardening layer (artifact `common-persistence`, depends on `common-models`). Small, focused surface: SQL statement rewriting for safety, a cross-dialect sequence helper, and SQL string sanitizing. Does not pull in any specific JDBC driver — the consumer picks `postgresql`/`mysql`/`oracle`/etc.

**Auto-configuration**: `com.vandunxg.common.persistence.config.JpaDatabaseAutoConfiguration` (`@AutoConfiguration`, `@ConditionalOnClass(StatementInspector.class)`), registers:

- `SafeSqlInterceptor` bean — gated by `vandunxg.common.persistence.sql-inspector.enabled` (default `true`)
- `HibernatePropertiesCustomizer` that wires the interceptor into `hibernate.session_factory.statement_inspector` (only if the interceptor bean exists)
- `SeqRepository` bean → `SeqRepositoryImpl` (`@ConditionalOnMissingBean`)

**Config properties** (prefix `vandunxg.common.persistence.sql-inspector`):

| Property           | Default | Effect                                                                              |
|--------------------|---------|-------------------------------------------------------------------------------------|
| `enabled`          | `true`  | Toggles the `SafeSqlInterceptor` bean entirely                                      |
| `in-clause-limit`  | `1000`  | Max `?` placeholders per `IN (...)` before it's chunked into `OR`-joined blocks     |
| `rewrite-like`     | `true`  | Rewrites `field LIKE ?` → `lower(field) like lower(?)` (or `unaccent(...)` variant) |
| `rewrite-large-in` | `true`  | Enables the `IN`-clause chunking rewrite                                            |
| `use-unaccent`     | `false` | Uses `unaccent(lower(...))` instead of plain `lower(...)` for the `LIKE` rewrite    |

- `SafeSqlInterceptor implements org.hibernate.resource.jdbc.spi.StatementInspector` — regex-rewrites Hibernate-generated SQL before execution. No manual wiring needed once the auto-config is active.
- `SeqRepository` interface — `nextValue(@Nullable String prefix, String seqName) → String`, `nextValue(String seqName) → BigInteger`. Default impl `SeqRepositoryImpl` tries `CREATE SEQUENCE IF NOT EXISTS %s` then `CREATE SEQUENCE %s` (swallowing "already exists"/`ORA-00955`/"duplicate" errors), then tries three `nextval` SQL dialect templates in order (`select nextval('%s')`, `select next value for %s`, `select %s.nextval from dual`) until one succeeds — this is how it stays portable across Postgres/SQL Server/Oracle-style sequence syntax without per-dialect config.
- `SqlUtils` — `encodeKeyword(@Nullable String) → @Nullable String` (wraps in `%...%` with `%`/`_`/`\` escaped, for safe `LIKE` search terms), `replaceSpecialCharacter(String) → String`, `sanitizeIdentifier(String) → String` (throws `IllegalArgumentException` if the identifier doesn't match `[A-Za-z_][A-Za-z0-9_$.]*` — use before interpolating any identifier, e.g. a sequence name, into native SQL).
- `QueryUtils` — `createOrderQuery(@Nullable PagingQuery, String alias) → StringBuilder` (JPQL `order by alias.field`, defaults to `createdAt desc` if no `sortBy`), `createOrderNativeQuery(...)` (same but native SQL, snake_cased field via `camelToSnake`), `camelToSnake(@Nullable String) → @Nullable String`.
- `PagingQuery` (extends `Query`, builder pattern) — base class for paged/sorted/keyword search request objects: `pageIndex` (default `1`), `pageSize` (default `30`), `sortBy`, `keyword`. Constants `ASC_SYMBOL="asc"`, `DESC_SYMBOL="desc"`, `DEFAULT_PAGE_INDEX=1`, `DEFAULT_PAGE_SIZE=30`.
- `BaseEntityRepositoryCustom<E extends AuditableEntity, Q extends PagingQuery>` (`@NoRepositoryBean`) — extend this for a repository that needs dynamic JPQL search+count. Provides `search(Q request) → List<E>` and `count(Q request) → Long`; you implement `protected abstract String createWhereQuery(Q query, Map<String,Object> values, StringBuilder joinClause)` to build the dynamic `WHERE` clause and bind values — the base class handles paging/ordering/parameter binding/query execution.

**Gotchas**: no JDBC driver is bundled — add your own. `SafeSqlInterceptor`'s `LIKE`/`unaccent` rewrite assumes Postgres-style `unaccent()` availability if `use-unaccent=true` (needs the Postgres `unaccent` extension installed). This module also ships its own `README.md` (`modules/common-persistence/README.md`) with the same content in narrative form.

---

### common-cache

Redis-backed caching (artifact `common-cache`, depends on `common-utils`). Layers custom AOP annotations on top of Spring's standard `@Cacheable`/`@CacheEvict` for the specific case of updating or evicting **one item inside** an already-cached `Collection` or `Map` — without invalidating (and re-fetching) the whole cache entry.

**Auto-configuration**: `com.vandunxg.common.cache.RedisCacheAutoConfiguration` (`@EnableCaching`, `@AutoConfiguration(after = DataRedisAutoConfiguration.class)`, `@ConditionalOnClass({CacheProperties.Redis.class, RedisCacheConfiguration.class})`), registers:

- `RedisSerializer<Object> redisValueSerializer` — `JsonRedisSerializer` wrapping the app's Jackson `JsonMapper` (bean name `"redisValueSerializer"`, override by defining your own bean of that name)
- `RedisCacheManager` — per-cache TTL/null-caching/key-prefix overrides via `spring.cache.custom-cache.<name>.*` (same shape as Spring Boot's own `spring.cache.redis.*`)
- `CacheAspect` — the AOP advice that powers `@CacheCollection`/`@CacheMap`/`@CacheUpdate`
- `CacheService<K,V>` default impl → `RedisCacheServiceImpl` (programmatic access)
- `KeyGenerator` bean named `"customKeyGenerator"` → `CustomKeyGenerator`
- `CacheErrorHandler` → `RedisCacheErrorHandler` — **logs and swallows** all cache get/put/evict/clear errors instead of throwing, so a Redis outage degrades to "always miss", never breaks the calling method.

**Config properties**: standard Spring Boot `spring.cache.type=redis`, `spring.data.redis.host/port`, `spring.cache.redis.time-to-live`/`cache-null-values`/`key-prefix`/`use-key-prefix` (all honored as defaults), plus per-cache overrides:

```yaml
spring:
  cache:
    custom-cache:
      users:
        time-to-live: 30m
      sessions:
        time-to-live: 1h
        cache-null-values: false
```

(`spring.cache.custom-cache` → `Map<String, CacheProperties.Redis>`, backed by `CustomCacheProperties`, prefix `spring.cache`.)

**Annotations** (package `com.vandunxg.common.cache.annotation`, all `@Target({METHOD,TYPE})`, `RUNTIME` retention, apply `@AfterReturning` — they only fire on successful method return):

- `@CacheCollection(cacheNames, key, compareProperties, condition="", action=CacheAction.PUT)` — loads the cached `Collection` at `key`; for each existing item, compares it to the method's return value using `compareProperties` (field names, reflective equality) or full `equals()` if `compareProperties` is empty. On match: `PUT` replaces that item with the return value, `EVICT` drops it from the collection. No match + `action=PUT` → appends the return value. Rewrites the whole collection back into the cache.
- `@CacheMap(cacheNames, key, keyMap, condition="", action=CacheAction.PUT)` — loads the cached `Map` at `key`; `PUT` does `map.put(keyMap, returnValue)`, `EVICT` does `map.remove(keyMap)`.
- `@CacheUpdate(collection = {@CacheCollection...}, map = {@CacheMap...})` — composes multiple `@CacheCollection`/`@CacheMap` updates off one method's return value (e.g. a `save()` that must update both an id-keyed map cache and a list cache in one shot).
- `CacheAction` enum: `PUT`, `EVICT`.
- `condition`/`key`/`keyMap` are SpEL expressions evaluated against the method's return value (`#result`/root object), args (by parameter name), `#target`, and `#method`.

Usage example (from this repo's `README.md`):

```java

@CacheCollection(cacheNames = "users", key = "#userId", compareProperties = {"id"}, action = CacheAction.PUT)
public UserDTO updateUser(Long userId, UserDTO dto) { ...}

@CacheUpdate(collection = {
  @CacheCollection(cacheNames = "users", key = "#result.id", compareProperties = {"id"}, action = CacheAction.PUT)
})
public UserDTO saveUser(UserDTO dto) { ...}
```

**`CacheService<K,V>`** (interface, package `service`) — programmatic alternative to annotations: `get(cacheName,key)`, `put(cacheName,key,value)`, `containsKey(cacheName,key)`, `evict(cacheName,key)`, `clear(cacheName)`. Inject as `CacheService<String, YourType>`.

**`CustomKeyGenerator`** — Spring `KeyGenerator` bean (`"customKeyGenerator"`), builds a stable key by reflecting over method parameters (handles `Collection`/`Map`/plain-object fields specially instead of relying on `toString()`/`hashCode()`). Use via `@Cacheable(keyGenerator = "customKeyGenerator")` when the default `SimpleKeyGenerator` doesn't produce stable/useful keys for complex parameter objects.

**`JsonRedisSerializer`** — stores cache values as JSON **with polymorphic type metadata** (`activateDefaultTyping(..., NON_FINAL)`), rebuilt from the app's own `JsonMapper` so custom modules/serializers still apply. **Gotcha**: this replaced a pre-2.0.0 ProtoStuff binary format — Redis entries cached before upgrading to 2.0.0+ are incompatible; flush the cache after upgrading across that boundary.

**`PageSerializer`** — a `tools.jackson.databind.ser.std.StdSerializer<PageImpl<?>>` that serializes Spring Data `Page` objects to `{number, numberOfElements, totalElements, totalPages, size, content}` — needed because `PageImpl` isn't reliably Jackson-serializable on its own; register/rely on it when caching or returning `Page<T>` results as JSON.

**`CacheConstants`**: `KEY="key"`, `DEFAULT="default"`.

---

### common-amqp

RabbitMQ publish-side abstraction (artifact `common-amqp`, no internal `common-*` dependency). Gives consumers a consistent async event envelope (correlation/trace id, timestamp, auto UUID) instead of hand-rolling `RabbitTemplate` calls.

**Auto-configuration**: `com.vandunxg.common.amqp.config.AmqpAutoConfiguration` (`@AutoConfiguration`, `@ConditionalOnProperty(name = "spring.rabbitmq.enabled", havingValue = "true")` — **the whole module is a no-op unless this property is explicitly `true`**, even with the RabbitMQ starter on the classpath). Registers:

- `MessageConverter` → `JacksonJsonMessageConverter` scoped to trusted package `"com.vandunxg"`
- `AmqpTemplate` → `RabbitTemplate` wired with that converter
- `ExecutorService` (bean `amqpTaskExecutor`, `Executors.newVirtualThreadPerTaskExecutor()`, `destroyMethod="shutdown"`) — publishes run on virtual threads, off the caller's thread
- `AmqpEventPublisher` default impl → `DefaultAmqpEventPublisher`
- `MessageRouteRegistry` default impl → `InMemoryMessageRouteRegistry` (in-process `ConcurrentHashMap`, not distributed/persisted)

Enable with:

```yaml
spring:
  rabbitmq:
    enabled: true
```

**`AmqpEventPublisher`** (interface) — all methods return `CompletableFuture<Void>`:

- `<T> publish(MessageRoute route, T payload)` — wraps in a fresh `MessageEnvelope`, fire-and-forget
- `<T> publish(MessageRoute route, T payload, Duration delay)` — same, plus sets an `x-delay` header (**requires the broker/exchange to support delayed delivery**, e.g. the `rabbitmq-delayed-message-exchange` plugin — otherwise the header is ignored and it publishes immediately)
- `<T> publish(MessageRoute route, MessageEnvelope<T> envelope)` / `..., Duration delay)` — use when you need to control/propagate `correlationId`/`traceId` yourself rather than let it auto-generate

```java
MessageRoute userRoute = MessageRoute.of("user.events", "user.created");
publisher.

publish(userRoute, event);                          // fire-and-forget
publisher.

publish(userRoute, event, Duration.ofSeconds(5));    // delayed (needs broker support)
  publisher.

publish(userRoute, event).

exceptionally(ex ->{log.

error("Send failed",ex); return null;});

MessageEnvelope<UserCreatedEvent> envelope = MessageEnvelope.wrap(event, correlationId, traceId);
publisher.

publish(userRoute, envelope);
```

**`MessageEnvelope<T>`** (record) — `messageId: UUID`, `correlationId: @Nullable String`, `traceId: @Nullable String`, `payload: T`, `timestamp: Instant`. Static factories: `wrap(T payload)` and `wrap(T payload, String correlationId, String traceId)` (both auto-generate `messageId`/`timestamp`).

**`MessageRoute`** (record) — `exchange: String`, `routingKey: String`; static `MessageRoute.of(exchange, routingKey)`.

**`MessageRouteRegistry`** (interface) — optional convenience so call sites don't hardcode routes: `register(Class<?> messageType, MessageRoute route)`, `find(Class<?> messageType) → Optional<MessageRoute>`.

**`UUIDMessage`** (abstract class) — base for domain event payloads needing a stable identity: protected no-arg constructor auto-generates a random `UUID id` (or pass one explicitly via the protected `UUIDMessage(UUID id)` ctor); `equals`/`hashCode`/`toString` by `id`.

```java
public class UserCreatedEvent extends UUIDMessage {
  private final String username;

  public UserCreatedEvent(String username) {
    super();
    this.username = username;
  }
}
```

**`QueueOptions`** — RabbitMQ queue-argument key constants for use when declaring `Queue` beans: `MESSAGE_TTL` (`x-message-ttl`), `AUTO_EXPIRE` (`x-expires`), `MAX_LENGTH_BYTES` (`x-max-length-bytes`), `DEAD_LETTER_EXCHANGE` (`x-dead-letter-exchange`), `DEAD_LETTER_ROUTING_KEY` (`x-dead-letter-routing-key`).

```java
args.put(QueueOptions.DEAD_LETTER_EXCHANGE, "dlx.exchange");
args.

put(QueueOptions.MESSAGE_TTL, 60_000);
```

---

### common-email

SMTP send + IMAP scan-and-extract (artifact `common-email`, no internal `common-*` dependency).

**Auto-configuration**: `com.vandunxg.common.email.config.EmailAutoConfiguration` (`@AutoConfiguration`, `@ConditionalOnClass(JavaMailSender.class)`), registers:

- `MailSenderFactory` (always, if `JavaMailSender` is on the classpath)
- `MailService` default impl → `MailServiceImpl` — **only if a `JavaMailSender` bean already exists** (`@ConditionalOnBean(JavaMailSender.class)`), i.e. you must configure `spring.mail.*` so Spring Boot's own mail auto-configuration creates that bean first.

**Config**: `spring.mail.host/port/username/password/from/protocol` (Spring Boot standard prefix; `MailProperties` here is a slim internal read of the same prefix used by `MailServiceImpl` to resolve a default "from" address — not a separate property namespace to set). Plus scanner-specific `@Value` properties on `MailServiceImpl` (not `@ConfigurationProperties`, so not in `spring-configuration-metadata.json` — set as plain properties):

| Property                       | Default |
|--------------------------------|---------|
| `mail-scanner.protocol`        | `imap`  |
| `mail-scanner.imap.ssl.enable` | `true`  |
| `mail-scanner.imap.auth`       | `true`  |
| `mail-scanner.folder`          | `INBOX` |

**`MailService`** (interface) — all send methods throw `MessagingException` except `sendSimpleMail`:

- `sendSimpleMail(to, subject, content, String... cc)` — plain text
- `sendHtmlMail(to, subject, content, String... cc)` — HTML, via the injected default `JavaMailSender`
- `sendHtmlMail(host, port, username, password, to, subject, content, String... cc)` — builds a **one-off** `JavaMailSender` per call via `MailSenderFactory` (different SMTP account than the default, e.g. per-tenant sending)
- `sendAttachmentsMail(to, subject, content, filePath, String... cc)` — single file attachment from local disk
- `sendAttachmentsMailBcc(bcc: List<String>, subject, content, filePaths: List<String>, fileNames: List<String>, logoPath, logoContentId, String... cc)` — multi-attachment + optional inline image (e.g. a logo embedded via `cid:`) + BCC-only send (no `to` recipient)
- `sendResourceMail(to, subject, content, rscPath, rscId, String... cc)` — inline embedded classpath resource (reference in HTML as `<img src="cid:rscId">`)
- `scanMessage(mailHost, mailPort, username, password, totalMessageScan, subjectMail, cssQuery) → List<MessageMail>` — connects over IMAP(S), reads the last `totalMessageScan` messages, keeps only ones whose subject **exactly equals** `subjectMail`, and for each runs a jsoup CSS-selector (`cssQuery`, default `"b"` if blank) over the HTML/plain/multipart body to pull out matching element text — built for parsing structured notification emails (e.g. bank transaction alerts).
- `getAttributes(@Nullable MimeMessage message, cssQuery) → List<String>` — the same jsoup extraction, standalone (returns `[]` for a null message).

**`MessageMail`** — builder-style value object: `contentAttributes: List<String>` (the jsoup-extracted values), `recipients`, `sendDate`, `receivedDate`.

```java
mailService.sendHtmlMail("to@example.com","Subject","<h1>Hello</h1>");
mailService.

sendAttachmentsMail("to@example.com","Subject","<p>See attachment</p>","/path/to/file.pdf");
mailService.

sendHtmlMail("smtp.custom.com",587,"user","pass","to@example.com","Subject","<p>Content</p>");
```

**Gotchas**: `scanMessage` matches subjects by **exact equality**, not substring/regex — mismatches are silently skipped (logged at `info`). All failures inside `scanMessage`/`getAttributes` are caught and logged, returning an empty list rather than propagating — don't expect exceptions from these two methods, check for an empty result instead.

---

### common-client

**Module**: artifactId `common-client`, depends internally on `common-utils`, `common-models`, `common-cache`, `common-email`. External deps: `spring-boot-starter-web`, `spring-retry`, `org.apache.httpcomponents.client5:httpclient5`. 100 files — the largest module.

**Purpose.** A collection of pre-built HTTP client integrations for specific third-party/partner services: ACB bank (transactions/balance/QR code), CloudFlare (DNS record management), a custom firewall/proxy control-plane ("fw"), Mikrotik router/proxy partner API, and a static-proxy vendor ("ProxyVN"), plus a generic proxy-info lookup service. A consuming service injects the adapter interface for the provider it needs and `ClientAutoConfiguration` wires a working implementation, backed by a shared `HttpClientCustom` REST wrapper with pooled connections, error logging, and a shared retry policy. All adapters are `@ConditionalOnMissingBean` — a consumer can supply its own implementation to override any single one.

#### Adapters & services

All beans below are registered by `ClientAutoConfiguration` and only created if the consumer hasn't defined their own bean of that type.

**`adapter.bank.BankAdapter`** (impl `BankAdapterImpl`) — ACB bank operations. **`@ConditionalOnBean(MailService.class)`** — absent from the context unless `common-email`'s `MailService` bean is also active.

- `QrCodeDTO getQrCode(String cacheKey, QrCodeCreateRequest request)` — `@Cacheable("qr-bank-code")`; falls back to a manually-built web2m QR link if the remote QR API returns no data.
- `BankTransactionResponseDTO getTransaction()` / `getTransaction(bankUsername, bankPassword, account)`
- `BankAccountBalanceDTO getAccountBalance()` — **gotcha: actually calls `BankProperties.transactionUrl()`, not `balanceUrl()`** (dead config field).
- `List<BankTransactionDTO> getTransactionByEmail(host, port, username, password, BankMailFieldDTO)` — delegates to `common-email`'s `MailService.scanMessage(...)`, regex-parses each message body into a transaction.

**`adapter.cloudflare.CloudFlareAdapter`** (impl `CloudFlareAdapterImpl`) — CloudFlare v4 REST API DNS management.

- `CloudflareDTO createDns(domainName, type, name, content, proxied)` (+ overload with explicit `xAuthKey`/`xAuthEmail`)
- `Boolean updateIpForDns(domainName, name, content, newContent)` (+ explicit-credential overload)
- `Boolean updateDns(domainName, type, name, content, newContent)`
- `String getDnsRecordIdByNameAndContent(zoneId, name, content)`
  Resolves zone ID by domain, then record ID by name+content, before mutating. Treats CloudFlare's "record already exists" error as success.

**`adapter.fw.FwProxyAdapter`** (impl `FwProxyAdapterImpl`) — internal firewall/proxy control server (hardcoded paths under `{forwardDomain}`: `/api/proxy/open`, `/generate-proxy-auth`, `/remove-proxy-auth`, `/close`, `/allow-ip`, `/security`, `/extend`, `/change-proxy`). Auth via headers `client-id`/`client-secret` (`app.client.fw-client-id`/`app.client.fw-client-secret`, default blank).

- `FwOpenPortResponse openPort(forwardDomain, FwOpenPortRequest)`
- `FwGenerateAuthResponse generateProxyAuth(forwardDomain, fwProxyOpenPort)`
- `void removeProxyAuth(forwardDomain, fwProxyOpenPort, proxyUser, proxyPass)`
- `boolean closePort/allowIp/securityProxy/extendProxy/changeProxy(forwardDomain, <Request>)`

**`adapter.mikrotik.MikrotikPartnerAdapter`** (impl `MikrotikPartnerAdapterImpl`) — controls Mikrotik router "lines" hosting rotating/static HTTP+SOCKS5 proxies (`{syncDomain}/api/...`, `/health-check`).

- `ProxyDTO getProxy(syncDomain, secret, information)`
- `MikrotikResetPortResponse resetPortRotatingProxy(domain, secret, information)` — rate-limited via `ClientCacheService`.
- `void removeNatPort(syncDomain, lineSecret, information, portNeedRemoveNat)` — `@Async`.
- `ProxyGeneralResponse addNatPort/createProxy/securityProxy/removeProxy/changeProtocolProxy/changeIPProxy(...)`
- `Boolean healthCheckMikrotikBox(syncDomain, secret)`
- `List<MikrotikNatPortData> getAllNatPort(syncDomain, secret)`
  `information` is `"host:port"` (or `"user:pass@host:port"`), parsed via `ProxyUtils.getHttpsProxy`. Auto-generated port ranges: HTTP `11111–35000`, SOCKS5 `35001–65534`.

**`adapter.static_proxy.proxyvn.ProxyVnAdapter`** (impl `ProxyVnAdapterImpl`) — fetches/parses ProxyVN's plaintext proxy-list feed.

- `List<ProxyVnResponse> getListProxy(proxyListLink)` — tolerant parsing (strips BOM, repairs concatenated-JSON-objects into an array); returns empty list on any failure, never throws.

**`adapter.ProxyInformationService`** (impl `ProxyInformationServiceImpl`) — routes a lookup call through a given proxy.

- `<T> T getInformationOfProxy(url, ProxyInfoDTO, Class<T>)` — via `HttpClientCustom.executeWithProxy`.
- `String getLocation(ProxyInfoDTO)` — hardcoded to `http://json.wtfismyip.com`.

**`service.AcbBankAuthService`** (impl `AcbBankAuthServiceImpl`) — ACB token acquisition/caching (used internally by `BankAdapterImpl`).

- `String getAuthToken(bankUsername, bankPassword)` — caches token under `acbAuth_token` keyed by username; tracks consecutive login failures under `bankTransaction_loginError`, stops after 3 until evicted.

**`service.ClientCacheService`** (impl `ClientCacheServiceImpl`) — pure rate-limiting/dedup caches for Mikrotik rotate operations (each method `@Cacheable`, echoes input back — the cache side-effect is the point): `rotatingMikCache`, `rotatingAndSyncProxyMikCache`, `syncAndRotatingProxyMikCache`.

**`config.rest.HttpClientCustom`** (impl `HttpClientCustomImpl`) — the shared REST wrapper every adapter above is built on. All `execute*` variants return `null` on any exception (never throw), log via SLF4J: `execute(url, method, [headers,] body, class[, params...])`, `execute(url, method, body) → String`, `executeNonCheckResponse(...)` (parses body regardless of status), `executeWithPredefinedHeader(...)`, `executeWithProxy(ProxyInfoDTO, url, method, headers, body, class[, params...])` (builds a one-off `RestTemplate` per call, sets `Proxy-Authorization` if creds set), `executeWithConfig(HttpConfigDTO, ...)` (one-off `RestTemplate` with custom connect/read timeouts). **Note**: `common-web` has its own, much simpler, same-named `com.vandunxg.common.web.config.rest.HttpClientCustom` (thin `RestClient` wrapper, 6 methods) — different type, different package, don't confuse the two.

#### `ClientAutoConfiguration` and config properties

`com.vandunxg.common.client.config.ClientAutoConfiguration` (`@AutoConfiguration @EnableRetry`, `@ConditionalOnClass({RestTemplate.class, CloseableHttpClient.class})`, `@EnableConfigurationProperties({ConsumerProperties.class, BankProperties.class})`) — the sole `AutoConfiguration.imports` entry for this module. Registers (all `@ConditionalOnMissingBean`): `RestTemplateResponseErrorHandler`, `RestTemplate` (Apache HttpClient5, **trust-all SSL context**, pool sized from `ConsumerProperties`), `RetryTemplate` (fixed backoff = `ConsumerProperties.maxDelay` seconds), `List<RetryListener>` bean named `"retryListeners"` → `[RetryLogger]` (the well-known name `@EnableRetry` looks up to attach globally to `@Retryable` methods), `HttpClientCustom`, `AcbBankAuthService`, `ClientCacheService`, `ProxyInformationService`, `CloudFlareAdapter`, `FwProxyAdapter`, `MikrotikPartnerAdapter`, `ProxyVnAdapter`, `BankAdapter` (conditional on `MailService`).

**`BankProperties`** (record, prefix `bank`, no defaults): `username`, `secret`, `transactionUrl`, `loginUrl`, `balanceUrl` (unused, see gotcha), `clientId`.

**`ConsumerProperties`** (prefix `consumer`): `timeout` (default `5000`ms), `defaultMaxPerRoute` (default `20`), `maxTotal` (default `200`), `maxAttempts` (default `3`), `maxDelay` (default `1`s), nested `apiCloudflare` (prefix `consumer.api-cloudflare`, no defaults — `xAuthKey`, `xAuthEmail`, `baseUrl`, nested `uris.getZones`/`uris.createDnsRecords`; `CloudFlareAdapterImpl` throws `IllegalStateException` at call time if unset).

Additional plain `@Value`-bound settings (not in `spring-configuration-metadata.json`): `consumer.proxy-request-timeout`/`proxy-response-timeout` (default `5000` each, in `HttpClientCustomImpl`, duplicating/shadowing `ConsumerProperties` via a second binding path), `app.client.fw-client-id`/`fw-client-secret` (default blank, in `FwProxyAdapterImpl`).

**`MailSenderProperties`** (prefix `mail-scanner`, `@Component`, lives in this module's `config` package but is actually consumed by **`common-email`**'s `MailServiceImpl`/`MailSenderFactory`, not by anything in `common-client` itself): `username`, `password`, `protocol`, `host`, `port`, nested `iMap.imap.enable`/`iMap.auth`, `folder`. No defaults.

#### Cross-cutting REST error handling & retry

**`RestTemplateResponseErrorHandler`** — `hasError()` true for any HTTP error status, but `handleError()` **only logs, never throws** (parses body as a `common-models` `Response`, logs code/message, falls back to raw body). Practical effect: `RestTemplate` calls through this module's beans won't throw on 4xx/5xx — callers get the response body back and must check status themselves.

**Retry policy**: fixed backoff (`ConsumerProperties.maxDelay`); `HttpStatusCodeException` with 500/502/503/504 → retried up to `maxAttempts`; any other HTTP status → `NeverRetryPolicy`; non-`HttpStatusCodeException` (timeouts, connection refused) → retried regardless.

**Gotcha**: because the error handler never rethrows on HTTP error status, the 500/502/503/504 retry branch is largely dormant for calls made through `HttpClientCustom` — and **`HttpClientCustomImpl.execute*()` methods are not `@Retryable` and don't use the `RetryTemplate`**, so no automatic retry happens on adapter calls out of the box. A consumer wanting retries on their own code gets `RetryLogger` for free via the `"retryListeners"` bean name if they use `@Retryable` or the injected `RetryTemplate` directly.

**`RetryLogger`** (`RetryListener`) — `onError()` logs at WARN with retry count + exception; doesn't block/modify retries.

#### Module-specific exceptions & utils

- `supports.exception.BadRequestError` (enum, `ResponseError`) — `WRONG_CLIENT_ID_OR_CLIENT_SECRET` (400).
- `supports.exception.NotFoundError` (enum, `ResponseError`) — `USER_NOT_FOUND` (404). **Distinct from** `common-models.error.BadRequestError`/`NotFoundError` — different constants/codes, same interface; check the package.
- `supports.utils.DomainUtils` — null-safe, never throws: `getRootDomainOfRequest(HttpServletRequest)`, `getTopLevelDomain/getSubDomain/getRootDomain(url)`, `extractRootDomain(domain)`, `getHostFromUrl(url)`, `changePortOfDomain(url, newPort)`, `getIpAddress(url)`.
- `supports.utils.ProxyConst` — IP-lookup endpoint constants: `API_MY_IP`, `IF_CONFIG_LOCATION`, `WTF_MY_IP`, `IF_CONFIG`.
- `supports.utils.ProxyUtils` — `getHttpsProxy(proxyInfo) → ProxyInfoDTO` (parses `"host:port"`/`"user:pass:host:port"`/`"user:pass@host:port"`), `buildProxyByFormat(...)`, `buildProxyWithSecurityByFormat(...)`, `changeProxySecurity(...)` — all keyed off `ProxyFormatType` enum (`USERNAME_PASSWORD_AT_HOSTNAME_PORT`, `USERNAME_PASSWORD_COLON_HOSTNAME_PORT`, `HOSTNAME_PORT_COLON_USERNAME_PASSWORD`, `HOSTNAME_PORT`).
- `supports.enums.BankTransactionType {IN, OUT}`, `supports.enums.ProtocolType {HTTP, SOCKS5}`.

#### DTOs by provider (fields, not exhaustive — see source for full shape)

- **Bank (ACB)**: `BankAccountBalanceDTO`, `BankAuthDTO`, `BankMailFieldDTO` (email-parsing config: subject filter, CSS selector, scan count, money regex, column indices), `BankTransactionDTO`, `BankTransactionResponseDTO`, `dto.request.AcbBankAuthRequest`, `dto.response.acb.{AcbBankResponse, AcbBankTokenResponse, AcbBankAccountBalanceResponse, AcbBankGetTransactionResponse}`.
- **CloudFlare**: `CloudflareDTO`, `dto.request.cloudflare.CloudflareCreateOrUpdateDnsRequest`, `dto.response.cloudflare.{CloudflareCreateDnsResponse, CloudflareCreateDnsData, CloudflareGetDnsListResponse, CloudflareGetZonesResponse, CloudflareListDnsData, CloudflareListZonesData}`.
- **Firewall/proxy**: `dto.request.fw.{FwOpenPortRequest, FwAllowIpRequest, FwChangeProxyRequest, FwClosePortRequest, FwExtendProxyRequest, FwGenerateProxyAuthRequest, FwRemoveProxyAuthRequest, FwSecurityProxyRequest}`, `dto.response.fw.{FwOpenPortResponse, FwGenerateAuthResponse}`.
- **Mikrotik**: `dto.request.mikrotik.{MikrotikChangeIPProxyRequest, MikrotikChangeProtocolProxyRequest, MikrotikCreateProxyRequest, MikrotikRemoveProxyRequest, MikrotikSecurityProxyRequest}`, `dto.response.mikrotik.{MikrotikAllNatPortResponse, MikrotikHealthCheckResponse, MikrotikNatPortData, MikrotikNatPortResponse, MikrotikProxyResponse, MikrotikResetPortResponse, ProxyMikrotikInformationResponse}`.
- **Proxy (cross-provider)**: `ProxyDTO` (generic resolved-proxy shape), `ProxyInfoDTO` (low-level connection descriptor: host/port/user/pass/type), `dto.response.ProxyGeneralResponse`, `dto.response.ProxyInformationResponse` (maps `json.wtfismyip.com`'s fields), `dto.response.static_proxy.proxyvn.ProxyVnResponse`.
- **QR code**: `QrCodeDTO`, `dto.request.{QrCodeCreateRequest, QrCodeGenerateRequest}`, `dto.response.QrCodeClientResponse`.
- **HTTP config**: `HttpConfigDTO` (connectTimeout/readTimeout).
- **Google captcha**: `dto.response.google_capcha.GoogleCaptchaVerifyTokenResponse` — **unused/placeholder**, no adapter constructs it.

#### Gotchas (common-client)

- **SSL verification is disabled** in the default `RestTemplate` bean and in proxy-routed calls (trust-all `SSLContext` + `NoopHostnameVerifier`) — override the `RestTemplate` bean for security-sensitive external calls.
- **No automatic retry on adapter HTTP calls** despite `RetryTemplate`/`RetryLogger` existing — see retry section above.
- **`BankAdapter` requires a `MailService` bean** — silently absent from context if `common-email` isn't configured (no startup error, just an autowiring failure downstream).
- **CloudFlare requires full `consumer.api-cloudflare.*` config** or `IllegalStateException` at call time (not startup).
- **`bank.balance-url` is dead config** — balance actually hits `bank.transaction-url`.
- Three independent property prefixes gate different adapters (`bank.*`, `consumer.*`, `app.client.fw-client-id`/`-secret`) — Mikrotik/ProxyVN/ProxyInformationService take endpoint/secret info as method params instead.
- Two unrelated `BadRequestError`/`NotFoundError` enum pairs exist across `common-models`/`common-client` — import the right one.
- `ProxyVnAdapter`, `DomainUtils`, `ProxyUtils` never throw — failures are logged and swallowed; treat empty/null as "not available", don't rely on exceptions.

---

### common-web

Depends on `common-utils`, `common-models`, `common-persistence`. This is the "batteries included" web/security layer for a Spring Boot REST API service: OAuth2/JWT resource-server scaffolding, a global exception→JSON error-response translator, lenient Jackson (de)serializers, SpringDoc/Swagger wiring, i18n, request/action logging, and small servlet/security support utilities. **It is not a fully self-contained security starter**: only `TokenCacheAutoConfiguration` is registered as a real Spring Boot `@AutoConfiguration` (the module's `AutoConfiguration.imports`). Every other class (`WebSecurityConfig`, `WebConfiguration`, `JacksonConfiguration`, `TimeZoneConfig`, `LocaleConfig`, `ExceptionHandleAdvice`, `ForbiddenTokenFilter`, `NoHandlerFoundFilter`, `RegexPermissionEvaluator`, `CustomAuthenticationEntryPoint`, `HttpClientCustomImpl`, `LocaleStringServiceImpl`, `CustomWebDataBinderAdvice`) relies on component scanning — **the consumer's `@SpringBootApplication` must scan (
or `@Import`) package `com.vandunxg.common.web`** for these beans to exist at all.

#### Security / JWT — pre-built vs. consumer-must-provide

| Class                                                                                   | Role                                                                  | Pre-configured                                                                                                                                                                                                                                                                                                                                                                                 | Consumer MUST provide                                                                                                                                                                                                                      |
|-----------------------------------------------------------------------------------------|-----------------------------------------------------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `security.WebSecurityConfig`                                                            | `@Configuration @EnableWebSecurity @Order(101)`                       | Exposes `AuthenticationManager` bean                                                                                                                                                                                                                                                                                                                                                           | **The entire `SecurityFilterChain` bean.** No filter chain, no `oauth2ResourceServer()` wiring, no `authorizeHttpRequests()` rules exist anywhere in this module.                                                                          |
| —                                                                                       | JWT validation                                                        | `spring-boot-starter-oauth2-resource-server`, `nimbus-jose-jwt`, `jjwt-*` are dependencies                                                                                                                                                                                                                                                                                                     | `spring.security.oauth2.resourceserver.jwt.issuer-uri`/`jwk-set-uri` in the consumer's own properties. **No `JwtDecoder` bean is defined here.**                                                                                           |
| —                                                                                       | Mapping JWT claims → `Authentication`                                 | `UserAuthentication` (from `common-models`) is the type every utility below expects                                                                                                                                                                                                                                                                                                            | **No `JwtAuthenticationConverter`/`Converter<Jwt,AbstractAuthenticationToken>` exists in this module.** Without one producing a `UserAuthentication`, `SecurityUtils`'s user/token/permission lookups all fall through to empty/exception. |
| `security.RegexPermissionEvaluator` (`@Component`)                                      | `PermissionEvaluator` for `@PreAuthorize("hasPermission(...)")`       | Casts `Authentication` to `UserAuthentication`; matches `permission.toString()` against each granted permission via `Pattern.matches(grantedPermission, requiredPermission)` (granted = regex, required = literal); `"all:manage"` is a wildcard super-permission. Throws `ResponseException(AuthorizationError.NOT_SUPPORTED_AUTHENTICATION)` if `Authentication` isn't `UserAuthentication`. | Consumer registers this evaluator on a `DefaultMethodSecurityExpressionHandler` themselves (no `@EnableMethodSecurity` auto-wiring).                                                                                                       |
| `security.ForbiddenTokenFilter` (`@Component`, `OncePerRequestFilter`)                  | Rejects requests bearing a blacklisted (logged-out/deactivated) token | Skips when unauthenticated/anonymous; otherwise extracts the Bearer token, calls `tokenCacheService.isInvalidToken(token)`; on hit, writes a 401 `ErrorResponse` directly (bypasses `ExceptionHandleAdvice`).                                                                                                                                                                                  | Needs a `TokenCacheService` bean in context — constructor-injected, so its absence fails app startup.                                                                                                                                      |
| `security.NoHandlerFoundFilter` (`@Component @WebFilter`, `OncePerRequestFilter`)       | Filter-level 404                                                      | Walks `DispatcherServlet.getHandlerMappings()`; no match → writes a 404 `ErrorResponse` directly, short-circuits the chain.                                                                                                                                                                                                                                                                    | Nothing extra — self-contained. Note: `ExceptionHandleAdvice` *also* handles `NoHandlerFoundException`, but only fires if `spring.mvc.throw-exception-if-no-handler-found=true` — the two 404 paths are independent/overlapping.           |
| `support.CustomAuthenticationEntryPoint` (`@Component("restAuthenticationEntryPoint")`) | 401 entry point                                                       | Delegates to Spring MVC's `HandlerExceptionResolver` so an `AuthenticationException` routes back through `ExceptionHandleAdvice` instead of a bare 401.                                                                                                                                                                                                                                        | Consumer must reference bean name `restAuthenticationEntryPoint` when building the `SecurityFilterChain`'s `.exceptionHandling(...)`.                                                                                                      |
| `security.TokenCacheService`/`TokenCacheServiceImpl`                                    | Token-blacklist abstraction                                           | `invalidToken`/`invalidRefreshToken`/`isInvalidToken`/`isInvalidRefreshToken`/`isExisted(cacheName, token)`; thin wrapper over `CacheManager` (cache names literally `"invalid-access-token"`/`"invalid-refresh-token"`), swallows exceptions and logs.                                                                                                                                        | —                                                                                                                                                                                                                                          |
| `config.TokenCacheAutoConfiguration` (`@AutoConfiguration`)                             | The only real auto-configuration here                                 | `@ConditionalOnBean(CacheManager.class)` + `@ConditionalOnMissingBean(TokenCacheService.class)` → registers `TokenCacheServiceImpl`. No `@ConfigurationProperties`.                                                                                                                                                                                                                            | **Consumer must provide a `CacheManager` bean** with cache regions `invalid-access-token`/`invalid-refresh-token` — and since `ForbiddenTokenFilter` hard-requires a `TokenCacheService`, missing both **breaks application startup**.     |

#### `SecurityUtils` (`support` package) — current-user lookup, the most-reused utility

All static, read from `SecurityContextHolder.getContext().getAuthentication()`:

- `Optional<String> getCurrentUser()` — username, via `extractPrincipal` (supports `UserDetails`→`getUsername()` or a raw `String` principal).
- `String extractPrincipal(Authentication)` — the underlying helper, public/reusable directly.
- `Optional<String> getCurrentUserJWT()` — raw bearer token, only for `UserAuthentication`.
- `Optional<UUID> getCurrentUserLoginId()` — authenticated user's UUID, only for `UserAuthentication`.
- `Optional<UserAuthentication> getUserAuthentication()` — the whole object, optionally.
- `UserAuthentication authentication()` — same but **throws `ResponseException(AuthenticationError.UNAUTHORISED)`** instead of returning empty.

**Gotcha**: none of the `UserAuthentication`-specific methods work unless the consumer's JWT converter actually produces a `UserAuthentication` — with vanilla Spring Security JWT defaults (`JwtAuthenticationToken`), they silently return empty/throw; only `getCurrentUser()` still works via the `UserDetails`/`String` fallback.

#### `ExceptionHandleAdvice` (`@ControllerAdvice`) — the global exception→JSON mapping

Response bodies are `ErrorResponse` (base) or `InvalidInputResponse extends ErrorResponse<Void>` (adds `errors: Set<FieldErrorResponse>`).

| Exception                                                                                                                                                                                                                                                                                 | HTTP status                          | Notes                                                                                                                                                                     |
|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|--------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `ResponseException` (common-models)                                                                                                                                                                                                                                                       | `e.getError().getStatus()` (dynamic) | **The primary exception type application code should throw.** Message i18n-resolved via `LocaleStringService` using `error.getName()` as key + `e.getParams()`.           |
| `ShortResponseException`                                                                                                                                                                                                                                                                  | dynamic                              | Same i18n resolution as `ResponseException`.                                                                                                                              |
| `CustomResponseException`                                                                                                                                                                                                                                                                 | dynamic                              | Message **not** i18n-resolved — `e.getMessage()` verbatim.                                                                                                                |
| `ForwardInnerAlertException`                                                                                                                                                                                                                                                              | —                                    | **Not handled here at all** — no `@ExceptionHandler` for it; must be caught by the consumer's own filter.                                                                 |
| `MethodArgumentNotValidException` (`@Valid` body)                                                                                                                                                                                                                                         | 400                                  | `InvalidInputResponse`, one `FieldErrorResponse` per bean-validation error, i18n-resolved.                                                                                |
| `ConstraintViolationException`                                                                                                                                                                                                                                                            | 400                                  | `InvalidInputResponse`, per-violation field errors.                                                                                                                       |
| `BindException`                                                                                                                                                                                                                                                                           | 400                                  | `InvalidInputResponse`, per-field errors.                                                                                                                                 |
| `HttpMessageNotReadableException`                                                                                                                                                                                                                                                         | 400                                  | Inspects Jackson cause (`InvalidFormatException` special-cases enums, lists valid constants; else generic).                                                               |
| `MethodArgumentTypeMismatchException`, `MissingPathVariableException`, `MissingRequestHeaderException`, `MissingServletRequestParameterException`, `MissingServletRequestPartException`, `MultipartException`, `MaxUploadSizeExceededException`, `HttpRequestMethodNotSupportedException` | 400/405                              | `InvalidInputResponse` variants.                                                                                                                                          |
| `ObjectOptimisticLockingFailureException`                                                                                                                                                                                                                                                 | 423 LOCKED                           | `BadRequestError.RECORD_IS_BEING_UPDATED`.                                                                                                                                |
| `AccessDeniedException`                                                                                                                                                                                                                                                                   | 403                                  | `AuthorizationError.ACCESS_DENIED`.                                                                                                                                       |
| `BadCredentialsException`, `InternalAuthenticationServiceException`                                                                                                                                                                                                                       | 401                                  | `e.getMessage()` verbatim.                                                                                                                                                |
| `InsufficientAuthenticationException`                                                                                                                                                                                                                                                     | 401                                  | Message **not i18n-resolved** (inline-built).                                                                                                                             |
| `AuthenticationException` (catch-all, broadest)                                                                                                                                                                                                                                           | 401                                  | `AuthenticationError.UNAUTHORISED` code, message via i18n key `AuthorizationError.NOT_SUPPORTED_AUTHENTICATION` (mismatched error family — a cosmetic bug in the source). |
| `NoHandlerFoundException`                                                                                                                                                                                                                                                                 | 404                                  | Only fires if `throw-exception-if-no-handler-found=true`.                                                                                                                 |
| `DataIntegrityViolationException`/`NonTransientDataAccessException`/`DataAccessException`                                                                                                                                                                                                 | 500                                  | `InternalServerError.DATA_ACCESS_EXCEPTION`.                                                                                                                              |
| `InvocationTargetException`, `Exception` (catch-all)                                                                                                                                                                                                                                      | 500                                  | `InternalServerError.INTERNAL_SERVER_ERROR`.                                                                                                                              |

#### Jackson — `JacksonConfiguration` + custom (de)serializers (`config.jackson`)

Registers a `JsonMapperBuilderCustomizer` adding Zalando `ProblemModule` plus custom (de)serializer modules. Hibernate modules are discovered from the consuming application's classpath. Uses Jackson 3 (`tools.jackson.*`).

| Type                                                                    | Direction | Behavior                                                                                  |
|-------------------------------------------------------------------------|-----------|-------------------------------------------------------------------------------------------|
| `String` (`CustomStringDeserializer`)                                   | deser     | Rejects number/boolean tokens; `null`→`null`; trims strings.                              |
| `Long`/`Integer` (`CustomLongDeserializer`/`CustomIntegerDeserializer`) | deser     | Only accepts a JSON integer token — rejects e.g. `"123"` string or floats.                |
| `UUID` (`CustomUUIDDeserializer`)                                       | deser     | String-only; blank/null→`null`; invalid format→mismatch error; trims first.               |
| `Instant` (`CustomInstantDeserializer`)                                 | deser     | Only JSON integer token, interpreted as **epoch millis** — ISO-8601 strings are rejected. |
| `LocalDate` (`CustomLocalDateDeserializer`/`CustomLocalDateSerializer`) | both      | String-only, format `yyyy-MM-dd`; blank/null→`null` on read.                              |

Net effect: strict JSON binding (no silent string→number coercion), but blank strings tolerated as null for UUID/LocalDate, and `Instant` fields must be epoch millis, not ISO strings.

#### SpringDoc/Swagger

`config.AbstractSwaggerConfig` is **abstract** — extend it and implement `protected abstract Info metadata()`. Supplies `customOpenAPI()` (adds a `bearerAuth` Bearer/JWT security scheme globally) and `customGlobalHeaders()` (injects an optional `Accept-Language` header param into every operation). `config.ApiDocumentHandlerInterceptor` reads each `@Operation.summary()` into request attribute `"custom_api_doc"` for logging; auto-registered by `WebConfiguration.addInterceptors()` — no separate registration needed once `WebConfiguration` is scanned.

#### Repository abstraction & auditing

- `support.DomainRepository<D,I>` (interface) — `findById`, `queryById`, `findAllByIds`, `save`, `saveAll`, `getById`.
- `support.AbstractDomainRepository<D,E,I>` — wraps a `JpaRepository<E,I>` + `common-models`'s `EntityMapper<D,E>`; read methods `@Transactional(readOnly=true)`, writes `@Transactional`; overridable hooks `enrich(D)`/`enrichList(List<D>)` (no-op by default) for post-mapping hydration. **Gotcha: `getById(I)` is left unimplemented** — every subclass must implement its own not-found semantics.
- `config.SpringSecurityAuditorAware implements AuditorAware<String>` — `getCurrentAuditor()` = `SecurityUtils.getCurrentUser().orElse("anonymous")`, always `Optional.of(...)`. Consumer must register it as a bean and `@EnableJpaAuditing(auditorAwareRef=...)` themselves.

#### i18n

`i18n.LocaleConfig` (`@Configuration`, extends `AcceptHeaderLocaleResolver`) — default locale hardcoded `vi`; supported `{en, vi}`; resolves solely via the standard `Accept-Language` header (the non-standard `kc-language` header override was removed in 3.0.1 — it was shadowing `Accept-Language` and, in `LocaleStringServiceImpl`, silently disabling locale resolution whenever `kc-language` was absent). Exposes `MessageSource` bean (`messageResourceTp`) reading `classpath:i18n/messages*.properties` (UTF-8, 60s cache) — **consumer must supply these message bundles themselves**, none are bundled. `LocaleStringService`/`LocaleStringServiceImpl` (`@Component`) — `getMessage(code, defaultMessage, params...)` looks up the message source, falls back to `MessageFormat.format(defaultMessage, params)` if the key is missing.

#### Remaining support/config classes

| Class                                                 | Purpose                                                                                                                                                                                                                                                                                                                                                                       |
|-------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `config.WebConfiguration`                             | General MVC config; `corsFilter()` bean is **wide-open** (`*` origin/methods/headers) restricted to `/api/**` + `/v2/api-docs` — override for production; registers `LocaleChangeInterceptor` (param `lang`) + `ApiDocumentHandlerInterceptor`.                                                                                                                               |
| `config.ActionLogFilter`                              | Logs every `/api/**` request/response (method, URI, status, duration, body, exception). `@WebFilter @Order(100)` — **no `@Component`**, needs `@ServletComponentScan` (or manual `FilterRegistrationBean`) in addition to package scanning. Wraps requests in `CachedHttpServletRequestWrapper`, masks `password`/`clientSecret` in logged bodies, puts IP+username into MDC. |
| `config.CustomWebDataBinderAdvice`                    | `@ControllerAdvice @InitBinder` for form/`@ModelAttribute`-bound params — registers editors for `Instant`, `Long`, `Integer`, `UUID`, `LocalDate`, plus a trimming `StringTrimmerEditor`; parse failures throw `ResponseException(BadRequestError.INVALID_INPUT)`.                                                                                                            |
| `config.TimeZoneConfig`                               | `@PostConstruct` forces JVM default timezone from `${spring.application.time_zone}` — **required, no default**, missing it fails startup.                                                                                                                                                                                                                                     |
| `support.CachedHttpServletRequestWrapper`             | Lets filters read the request body more than once (used by `ActionLogFilter`).                                                                                                                                                                                                                                                                                                |
| `support.KeyStoreKeyFactory`                          | Loads an RSA `KeyPair` from a Java KeyStore for JWT signing — `getKeyPair(alias)`.                                                                                                                                                                                                                                                                                            |
| `support.IpUtils` (`common-web`)                      | Servlet-aware client IP extraction — `getHostIp(HttpServletRequest)`, `getRemoteIp(HttpServletRequest)` (checks `x-original-forwarded-for`→`cf-connecting-ip`→`X-Real-IP`→`getRemoteAddr()`). **Distinct from** `common-utils`'s `IpUtils` (pure network validation, no servlet dependency).                                                                                  |
| `config.rest.HttpClientCustom`/`HttpClientCustomImpl` | Minimal `RestClient`-based wrapper (6 methods) — **different, simpler class from `common-client`'s same-named type** (that one wraps `RestTemplate` with proxy/retry/config support). Plain `@Component` (not conditional); swallows exceptions, returns `null` on failure.                                                                                                   |

#### Gotchas / required consumer wiring checklist

1. No `SecurityFilterChain` bean — must be authored by the consumer.
2. No JWT→`UserAuthentication` converter — without one, `SecurityUtils` current-user/JWT/permission methods don't work as intended.
3. `CacheManager` bean is mandatory for `TokenCacheAutoConfiguration`; combined with `ForbiddenTokenFilter`'s hard requirement on `TokenCacheService`, omitting both **breaks application startup**.
4. Only `TokenCacheAutoConfiguration` is real auto-configuration — everything else needs component scanning of `com.vandunxg.common.web` (or explicit `@Import`).
5. `ActionLogFilter` needs `@ServletComponentScan` in addition to package scanning (no `@Component`, unlike the security filters).
6. `WebConfiguration.corsFilter()` is wide-open by default — treat as dev-friendly, override for production.
7. `TimeZoneConfig` requires `spring.application.time_zone` or the app fails to start.
8. Default locale is hardcoded Vietnamese (`vi`); consumer must supply `i18n/messages*.properties`.
9. `AbstractDomainRepository.getById(I)` is unimplemented — every subclass must implement it.
10. Two independent 404 mechanisms coexist (`NoHandlerFoundFilter` always active; `ExceptionHandleAdvice`'s handler only fires with `throw-exception-if-no-handler-found=true`).
11. Don't confuse `common-web`'s `HttpClientCustom`/`IpUtils` with the same-named classes in `common-client`/`common-utils`.
