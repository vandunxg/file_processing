# vandunxg-common — Library Contract Reference (for AI coding agents)

> **Purpose of this file:** this is a machine-oriented API contract for the `vandunxg-common` Spring Boot library. It exists so an AI coding agent working in a *consumer* repository (a Vandunxg microservice that depends on this library) can check here **before** implementing utility code, DTOs, error handling, caching, messaging, email, HTTP client integrations, or web/security plumbing — because it likely already exists in this library. Prefer reusing what's documented here over reimplementing it. This file is generated from the library's source via CodeGraph exploration; verify against the actual dependency version in use if something looks off (check the library's `CHANGELOG.md` for behavior that changed between versions).

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

**Migration note:** 3.0.0 removes foundation APIs and transitive dependencies. Consult the library source's foundation migration guide before upgrading.

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

If the registry requires auth, add a `<server id="gitlab">` block with a `Private-Token` header to `~/.m2/settings.xml` — see the library's `README.md` for the exact snippet.

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
  verbatim because that is what the published artifact exposes. Consumer
  services **MUST NOT** use `Adapter` or `Port` as a naming suffix — see the
  `modular-architecture` skill.
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
