# Auth Register — Remaining Work (Persistence + Use Cases + Web)

<!-- prettier-ignore -->
> [!WARNING]
> **LEGACY ARCHITECTURE NOTICE — SUPERSEDED ARCHITECTURE GUIDANCE**
>
> Tài liệu này được tạo trước quyết định chuyển sang Pragmatic Modular DDD.
> Các package `adapter/*`, `port/*`, `*UseCase`, `*Port` và `*Adapter` trong tài
> liệu này mô tả legacy implementation và **không còn là architecture guidance**.
>
> This document predates the migration to Pragmatic Modular DDD. Every
> `adapter/in`, `adapter/out`, `port/in`, `port/out`, `*UseCase`,
> `*RepositoryPort`, and `*PersistenceAdapter` reference below records the
> legacy implementation **as it was actually built**. It is a historical record,
> not an instruction. Do not reproduce this layout, naming, or interface
> structure in new code or in a refactor.
>
> [`RULE.md`](../../../RULE.md) §4 is the source of truth for architecture. The
> business behavior, API contracts, and security requirements described here
> remain valid; only the structural guidance is superseded.

> **PLAN COMPLETED — DO NOT RE-EXECUTE.** Every task in this plan was implemented and merged. Its package layout and type names follow the legacy Hexagonal structure and are superseded by `RULE.md` §4. Kept for history only.
>
> <sub>Original agent instruction, retained verbatim for the record: **For agentic workers:** REQUIRED SUB-SKILL: `superpowers:subagent-driven-development`. Steps use checkbox (`- [ ]`) syntax for tracking.</sub>

**Context:** The domain foundation for register/verify/resend is already implemented and committed (`User`, `Role`, `UserRole`, `EmailVerificationToken`, `PasswordPolicy`, `AuthErrorCode`, `AuthDomainException` in `auth/domain/`), proven by `RegisterDomainTest` and `PasswordPolicyTest`. Flyway migrations `V202607170900`-`V202607170906` already create the full schema (`auth_users`, `role`, `role_permission`, `user_role`, `audit_logs`, seed data, `auth_email_verification_tokens`) and are on disk (untracked, not yet `git add`ed). Test infra (`PostgresIntegrationTest`, `PostgresTestContainerBase`, `application-test.yml`) already exists.

**What's missing:** the whole persistence adapter layer (currently stub/wrong), the register/verify/resend application services, supporting security adapters, and the web layer. This plan covers exactly that — nothing more. Login, JWT, full RBAC, admin user management are **out of scope** (per `docs/superpowers/specs/2026-07-17-auth-register-design.md`) — do not build them, do not build ports/adapters only they would need.

**Source of truth for scope/behavior:** `docs/superpowers/specs/2026-07-17-auth-register-design.md`. **Source of truth for code conventions:** `RULE.md` (package layout §3, naming §4, exceptions §6, MapStruct §7, logging §8, persistence §12, API §13, OpenAPI §14, testing §15). `AGENTS.md` governs the wider CSV-processing project (auth is infrastructure for it) — do not add Kafka/CQRS/messaging, do not create an interface for every class, keep controllers thin.

## Global Constraints

- Package layout is hexagonal per `RULE.md §3`: `domain/` has zero Spring/JPA imports; `application/` depends only on `domain/` + ports; `adapter/` is the only place with `@RestController`/`@Entity`/`@Repository`.
- Naming per `RULE.md §4`: `<Xxx>UseCase`, `<Xxx>RepositoryPort`, `<Xxx>Service`, `<Xxx>Command`, `<Xxx>Controller`, `<Xxx>Request`/`<Xxx>Response`, `<Xxx>Entity`, `Jpa<Xxx>Repository`, `<Xxx>PersistenceAdapter`, `<Xxx>PersistenceMapper`, `<Xxx>WebMapper`.
- Every `throw` that ends a request is preceded by a `log.warn`/`log.error` line with traceable identifiers, **never** the raw password or opaque token (`RULE.md §6.2`, §8.4). Mask email as `a***@domain` in any log line (never in the DB — full email is fine in `auth_users`/audit `data` is a protected store, not a log).
- MapStruct only, `@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)`; domain↔entity mappers implement `com.vandunxg.common.models.mapper.EntityMapper<D,E>` (`RULE.md §7`). No ModelMapper/BeanUtils/reflection copying.
- Constructor injection only (`@RequiredArgsConstructor`), never `@Autowired` on a field.
- `@Slf4j(topic = "AUTH-<FEATURE>")` per class; message format `[methodName] description key={} key={}` (`RULE.md §8.2`).
- Soft-delete convention is `deleted_at TIMESTAMPTZ` — already applied in existing migrations; do not add new soft-deletable entities in this plan.
- Migrations are append-only. The 7 files already on disk (`V202607170900` through `V202607170906`) must not be edited — only `git add` them as-is. Do not create a `V202607170907` unless a task below explicitly says to.
- `ddl-auto: validate` — every JPA entity's mapped columns must exist exactly as migrated. **`auth_email_verification_tokens` has no `created_at`/`last_modified_at`/`created_by`/`last_modified_by`/`version` columns** — its entity must be a plain `@Entity`, **not** extend `AuditableEntity`, even though the domain model `EmailVerificationToken extends AuditableDomain` (the inherited audit fields on the domain object simply stay unset for this aggregate — that's fine, nothing reads them).
- i18n keys must exist in both `src/main/resources/i18n/messages.properties` and `messages_vi.properties` (`RULE.md §6.4`). Existing `AuthErrorCode` values already have both — do not add new error codes unless a task explicitly requires one.
- `mvn spotless:apply` before every commit; `./mvnw -DskipTests clean compile` must succeed after every task.
- Reuse from `com.vandunxg.common.*` (`RULE.md §10`, `LIBRARY.md`): `IdUtils.nextId()` for IDs, `HashUtils.sha256(byte[])` for SHA-256 hex digests (token hash, IP hash), `common-email`'s `MailService.sendHtmlMail(to, subject, html)` for sending mail, `AuditableEntity`/`AuditableDomain` bases. Do **not** hand-roll SHA-256 or write a custom mailer.
- Do not build: `PasswordHasherPort.matches`, `ClockPort`/`IdGeneratorPort` interfaces, login/JWT anything, RBAC beyond looking up the `OPERATOR` role by code. If a task's code needs `Instant.now()`, inject a `java.time.Clock` bean (JDK type, not a custom port) — see Task 3.

## Agreed Interface Contracts (binding across all tasks — do not diverge)

```java
// application/port/out/UserRepositoryPort.java
public interface UserRepositoryPort {
  boolean existsByNormalizedUsername(String normalizedUsername);
  boolean existsByNormalizedEmail(String normalizedEmail);
  Optional<User> findById(UUID id);
  Optional<User> findByNormalizedIdentifier(String normalizedIdentifier); // '@' present -> email lookup, else username
  User save(User user); // MUST flush immediately (saveAndFlush) so unique-constraint races surface here, not at commit
}

// application/port/out/RoleRepositoryPort.java
public interface RoleRepositoryPort {
  Optional<Role> findByCode(String code); // active only: deleted_at IS NULL
}

// application/port/out/UserRoleRepositoryPort.java
public interface UserRoleRepositoryPort {
  UserRole save(UserRole userRole);
}

// application/port/out/AuditLogPort.java
public interface AuditLogPort {
  void record(AuditLog log);
}

// application/port/out/EmailVerificationTokenRepositoryPort.java
public interface EmailVerificationTokenRepositoryPort {
  EmailVerificationToken save(EmailVerificationToken token);
  Optional<EmailVerificationToken> findByTokenHashForUpdate(String tokenHash); // pessimistic write lock
  void invalidateAllForUser(UUID userId, Instant now); // bulk: used_at = now WHERE user_id=? AND used_at IS NULL
}

// application/port/out/PasswordHasherPort.java
public interface PasswordHasherPort {
  String hash(String rawPassword); // "{bcrypt}$2a$..." via DelegatingPasswordEncoder
}

// application/port/out/VerificationTokenGeneratorPort.java
public interface VerificationTokenGeneratorPort {
  String generate(); // 256-bit SecureRandom, Base64 URL-safe no-padding encoding of 32 raw bytes
}

// application/port/out/RegisterThrottlePort.java
public interface RegisterThrottlePort {
  boolean tryConsume(String key, int maxPerHour); // true = allowed (consumed one unit), false = limit exceeded
}

// application/port/out/EmailSenderPort.java
public interface EmailSenderPort {
  void sendVerificationEmail(String toEmail, String displayName, String verificationLink);
}
```

HTTP contract (binding for Task 7, stated here so earlier tasks build services that support it):

| Endpoint | Success | Errors |
|---|---|---|
| `POST /api/v1/auth/register` | `201` + body `{id, username, email, displayName, status}` | `409` duplicate username/email, `422` password policy, `429` throttled |
| `POST /api/v1/auth/verify-email` | `200` + body `{status}` | `410` unknown/expired/used token |
| `POST /api/v1/auth/resend-verification` | `204` always (enumeration-safe — same response whether the identifier is unknown, already verified, or a real pending account) | `429` throttled |

---

## Task 1: User + Role + UserRole persistence

**Files:**
- Rewrite: `auth/adapter/out/persistence/entity/UserEntity.java` (currently a wrong leftover stub — table `users`, only `id`+`deletedAt`)
- Rewrite: `auth/adapter/out/persistence/entity/JpaUserRepository.java` (currently empty marker interface)
- Rewrite: `auth/application/port/out/UserRepositoryPort.java` (currently `public class UserRepositoryPort {}`)
- Create: `auth/adapter/out/persistence/mapper/UserPersistenceMapper.java`
- Create: `auth/adapter/out/persistence/UserPersistenceAdapter.java`
- Create: `auth/adapter/out/persistence/entity/RoleEntity.java`
- Create: `auth/adapter/out/persistence/entity/JpaRoleRepository.java`
- Create: `auth/adapter/out/persistence/mapper/RolePersistenceMapper.java`
- Create: `auth/application/port/out/RoleRepositoryPort.java`
- Create: `auth/adapter/out/persistence/RolePersistenceAdapter.java`
- Create: `auth/adapter/out/persistence/entity/UserRoleEntity.java`
- Create: `auth/adapter/out/persistence/entity/JpaUserRoleRepository.java`
- Create: `auth/adapter/out/persistence/mapper/UserRolePersistenceMapper.java`
- Create: `auth/application/port/out/UserRoleRepositoryPort.java`
- Create: `auth/adapter/out/persistence/UserRolePersistenceAdapter.java`
- Create: `auth/configuration/AuthPersistenceConfiguration.java` — `@Configuration @EnableJpaAuditing(auditorAwareRef = "springSecurityAuditorAware") @EnableJpaRepositories(basePackages = "com.vandunxg.file_processing.auth.adapter.out.persistence.entity")`
- Create test: `src/test/java/com/vandunxg/file_processing/auth/adapter/out/persistence/MigrationAndSeedIT.java`
- Create test: `src/test/java/com/vandunxg/file_processing/auth/adapter/out/persistence/UserPersistenceAdapterIT.java`
- `git add` the 7 untracked migration files under `src/main/resources/db/migration/` as part of this task's commit (they are prerequisite schema, not yet staged).

**Current domain model to map against** (already implemented, do not modify): `User` has fields `id, username, normalizedUsername, email, normalizedEmail, displayName, passwordHash, status(UserStatus), roles(Set<Role>), mustChangePassword, failedLoginCount, lockedUntil, credentialVersion, passwordChangedAt, emailVerifiedAt, deletedAt, version`. `Role` has `id, code, status(ActiveStatus), deletedAt`. `UserRole` has `id, userId, roleId, deletedAt` with constructor `UserRole(UUID userId, UUID roleId)`.

- [ ] **Step 1: `UserEntity`** — `@Entity @Table(name = "auth_users")`, extends `AuditableEntity`, one column per migration `V202607170900` (`id, username, normalizedUsername, email, normalizedEmail, displayName, passwordHash, status(@Enumerated STRING), mustChangePassword, failedLoginCount, lastFailedLoginAt, lockedUntil, credentialVersion, lastLoginAt, passwordChangedAt, emailVerifiedAt, deletedAt, @Version version`). Note the migration has `last_failed_login_at` and `last_login_at` columns that the current `User` domain model does **not** have fields for — map them on the entity anyway (JPA needs every column represented) but the mapper ignores them on `toDomain`/`toEntity` (comment why only if genuinely non-obvious; here it's just "domain doesn't track these yet in this delivery" — a one-liner is fine).
- [ ] **Step 2: `JpaUserRepository`** — methods: `existsByNormalizedUsernameAndDeletedAtIsNull`, `existsByNormalizedEmailAndDeletedAtIsNull`, `Optional<UserEntity> findByIdAndDeletedAtIsNull(UUID id)`, `Optional<UserEntity> findByNormalizedUsernameAndDeletedAtIsNull(String v)`, `Optional<UserEntity> findByNormalizedEmailAndDeletedAtIsNull(String v)`.
- [ ] **Step 3: `UserPersistenceMapper`** — implements `EntityMapper<User, UserEntity>`; `toDomain` ignores `roles` (loaded separately, there is no role join in this delivery's `findById`/`findByNormalizedIdentifier` — `RegisterService`/`VerifyEmailService` never need `User.roles` populated after load, only at creation time where it's already in memory); `toEntity` ignores `createdAt/lastModifiedAt/createdBy/lastModifiedBy` (audit-managed) and ignores `lastFailedLoginAt/lastLoginAt` (not in domain model — map with `@Mapping(target = "...", ignore = true)` on whichever direction lacks the field, `unmappedTargetPolicy = ERROR` will otherwise fail the build, which is the point).
- [ ] **Step 4: `UserRepositoryPort`** — exact signature from the Agreed Interface Contracts section above.
- [ ] **Step 5: `UserPersistenceAdapter`** — `@Repository @RequiredArgsConstructor`. `save(User user)` calls `jpa.saveAndFlush(mapper.toEntity(user))` then `mapper.toDomain(saved)` — the `saveAndFlush` is load-bearing: it forces the unique-constraint check to run inside this call so `RegisterService` can catch `org.springframework.dao.DataIntegrityViolationException` here rather than at transaction commit (after the method has already returned). `findByNormalizedIdentifier`: if the identifier contains `@`, look up by normalized email, else by normalized username.
- [ ] **Step 6: `RoleEntity`** — `@Entity @Table(name = "role")`, extends `AuditableEntity`. Migration `V202607170901` has more columns (`role_inherited_id, name, description, is_const`) than the current `Role` domain model exposes (`id, code, status, deletedAt`) — map every column on the entity (needed for `ddl-auto: validate`), ignore the extras on the mapper's `toDomain` (`name`, `description`, `roleInheritedId`, `isConst` have no domain field to receive them) and provide safe entity-side defaults on `toEntity` if ever used (this delivery only reads roles, never writes one, so `toEntity`/`save` on `RoleRepositoryPort` is intentionally **not** exposed — read-only port).
- [ ] **Step 7: `JpaRoleRepository`** — `Optional<RoleEntity> findByCodeAndDeletedAtIsNull(String code)`.
- [ ] **Step 8: `RolePersistenceMapper`**, **`RoleRepositoryPort`** (read-only: `findByCode` only — do not add `save`, it would be dead code per YAGNI), **`RolePersistenceAdapter`**.
- [ ] **Step 9: `UserRoleEntity`** — `@Entity @Table(name = "user_role")`, extends `AuditableEntity`, columns per `V202607170903` (`id, userId, roleId, deletedAt`).
- [ ] **Step 10: `JpaUserRoleRepository`** (plain `JpaRepository`, no custom queries needed — this delivery only inserts), **`UserRolePersistenceMapper`**, **`UserRoleRepositoryPort`** (`save` only), **`UserRolePersistenceAdapter`**.
- [ ] **Step 11: `AuthPersistenceConfiguration`** as specified above.
- [ ] **Step 12: `MigrationAndSeedIT`** (`@PostgresIntegrationTest`, extends `PostgresTestContainerBase`) — asserts all 7 migrations apply cleanly, `role` table has exactly 2 rows (`ADMIN`, `OPERATOR`, both `is_const = true`), `role_permission` has 1 row for ADMIN (`ALL`/`MANAGE`) and 10 for OPERATOR, `auth_email_verification_tokens` table exists (assert via a trivial insert+select round-trip through `JpaRoleRepository`/raw `JdbcTemplate`, or simply that `EmailVerificationTokenEntity` — created in Task 2 — round-trips; if Task 2 isn't done yet when this test is written, assert the role/permission seed only — Task 2 will extend this file).
- [ ] **Step 13: `UserPersistenceAdapterIT`** — register two users with the same normalized username concurrently (two threads / two transactions) via `UserRepositoryPort.save`, assert exactly one succeeds and the other throws `DataIntegrityViolationException` (or its Spring-translated subtype) — this proves `saveAndFlush` surfaces the race synchronously, which `RegisterService` (Task 4) depends on.
- [ ] **Step 14: Build + test**: `./mvnw -DskipTests clean compile` then `./mvnw -Dtest='MigrationAndSeedIT,UserPersistenceAdapterIT' test`. Both green.
- [ ] **Step 15: Commit** `feat(auth): add User/Role/UserRole persistence adapters and stage schema migrations`.

## Task 2: AuditLog + EmailVerificationToken persistence

**Files:**
- Create: `auth/adapter/out/persistence/entity/AuditLogEntity.java`
- Create: `auth/adapter/out/persistence/entity/JpaAuditLogRepository.java`
- Create: `auth/adapter/out/persistence/mapper/AuditLogPersistenceMapper.java`
- Create: `auth/application/port/out/AuditLogPort.java`
- Create: `auth/adapter/out/persistence/AuditLogPersistenceAdapter.java`
- Create: `auth/adapter/out/persistence/entity/EmailVerificationTokenEntity.java`
- Create: `auth/adapter/out/persistence/entity/JpaEmailVerificationTokenRepository.java`
- Create: `auth/adapter/out/persistence/mapper/EmailVerificationTokenPersistenceMapper.java`
- Create: `auth/application/port/out/EmailVerificationTokenRepositoryPort.java`
- Create: `auth/adapter/out/persistence/EmailVerificationTokenPersistenceAdapter.java`
- Modify: `auth/domain/model/OperationType.java` — add `USER_REGISTERED, EMAIL_VERIFICATION_REQUESTED, EMAIL_VERIFIED` (keep existing `CREATE, UPDATE, DELETE, ACTIVATED, DEACTIVATED`)
- Modify: `auth/domain/model/AuditLogDomain.java` — currently `public enum AuditLogDomain {}` (empty!) — add `AUTH`
- Create test: `src/test/java/com/vandunxg/file_processing/auth/adapter/out/persistence/EmailVerificationTokenPersistenceAdapterIT.java`

**Current domain model** (do not modify): `AuditLog` extends `AuditableDomain`, fields `id, domain(AuditLogDomain), objectId, operation(OperationType), changedBy, changedAt, data(Map<String,Object>), ipAddress, browser, userAgent, deletedAt`. `EmailVerificationToken` extends `AuditableDomain`, fields `id, userId, tokenHash, issuedAt, expiresAt, usedAt, ipAddressHash`, factory `issue(...)`, behavior `consume(Instant now)` (throws `AuthDomainException(EMAIL_VERIFICATION_TOKEN_INVALID)` if not usable).

- [ ] **Step 1: `AuditLogEntity`** — `@Entity @Table(name = "audit_logs")`, extends `AuditableEntity`, columns per `V202607170904` including `data` as `@JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb")`.
- [ ] **Step 2: `JpaAuditLogRepository`** — plain `JpaRepository`, no custom queries (write-only in this delivery).
- [ ] **Step 3: `AuditLogPersistenceMapper`**, **`AuditLogPort`** (`void record(AuditLog log)` only), **`AuditLogPersistenceAdapter`** (`@Service`, `record` = `repository.save(mapper.toEntity(log))`).
- [ ] **Step 4: `EmailVerificationTokenEntity`** — **plain `@Entity`, do NOT extend `AuditableEntity`** (table has no audit columns — see Global Constraints). `@Table(name = "auth_email_verification_tokens")`. Columns: `id (UUID, @Id)`, `userId (UUID)`, `tokenHash (String, length=64)`, `issuedAt (Instant)`, `expiresAt (Instant)`, `usedAt (Instant, nullable)`, `ipAddressHash (String, length=64, nullable)`.
- [ ] **Step 5: `JpaEmailVerificationTokenRepository`**:
  ```java
  public interface JpaEmailVerificationTokenRepository
      extends JpaRepository<EmailVerificationTokenEntity, UUID> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM EmailVerificationTokenEntity t WHERE t.tokenHash = :hash")
    Optional<EmailVerificationTokenEntity> findByTokenHashForUpdate(@Param("hash") String hash);

    @Modifying
    @Query("UPDATE EmailVerificationTokenEntity t SET t.usedAt = :now "
         + "WHERE t.userId = :userId AND t.usedAt IS NULL")
    int invalidateAllForUser(@Param("userId") UUID userId, @Param("now") Instant now);
  }
  ```
- [ ] **Step 6: `EmailVerificationTokenPersistenceMapper`** — implements `EntityMapper<EmailVerificationToken, EmailVerificationTokenEntity>`. Since the entity has no audit columns, `toEntity` needs no `ignore` mappings for audit fields (there's nothing to ignore — MapStruct will simply not see `createdAt`/etc. as targets because the entity doesn't declare them).
- [ ] **Step 7: `EmailVerificationTokenRepositoryPort`** — exact signature from Agreed Interface Contracts. **`EmailVerificationTokenPersistenceAdapter`** — `save` uses plain `jpa.save` (no flush needed, no unique-race concern here beyond the DB's own `UNIQUE(token_hash)`, which will never collide given 256-bit entropy); `invalidateAllForUser` must run `@Transactional` (bulk `@Modifying` query).
- [ ] **Step 8: `EmailVerificationTokenPersistenceAdapterIT`** (`@PostgresIntegrationTest`) covering: token round-trips with only its hash persisted (assert the stored row's `token_hash` matches an externally-computed SHA-256, and that no raw-token column exists on the entity); `findByTokenHashForUpdate` returns empty for an unknown hash; a token consumed via `token.consume(now)` then `save`d shows `usedAt` set and a second `consume` call throws; `invalidateAllForUser` sets `usedAt` on all of a user's previously-unused tokens and returns the count; expiry — a token issued with a 1-second TTL is `isExpired` after sleeping/advancing past it (use `Duration.ofMillis(1)` + a short real sleep, or issue with `now` already past `issuedAt+ttl` by constructing with a backdated `now` — prefer the latter, no `Thread.sleep` in tests).
- [ ] **Step 9: Extend `MigrationAndSeedIT`** (from Task 1) with an assertion that `auth_email_verification_tokens` exists and enforces `UNIQUE(token_hash)` (insert two rows with the same hash in one test, expect the second to fail).
- [ ] **Step 10: Build + test**: `./mvnw -Dtest='MigrationAndSeedIT,EmailVerificationTokenPersistenceAdapterIT' test`. Green.
- [ ] **Step 11: Commit** `feat(auth): add AuditLog and EmailVerificationToken persistence adapters`.

## Task 3: Security/infra adapters — password hasher, token generator, throttle, email sender, config

**Files:**
- Create: `auth/application/port/out/PasswordHasherPort.java`
- Create: `auth/adapter/out/password/BcryptPasswordHasherAdapter.java`
- Create: `auth/application/port/out/VerificationTokenGeneratorPort.java`
- Create: `auth/adapter/out/security/SecureVerificationTokenGeneratorAdapter.java`
- Create: `auth/application/port/out/RegisterThrottlePort.java`
- Create: `auth/adapter/out/cache/CaffeineRegisterThrottleAdapter.java`
- Create: `auth/application/port/out/EmailSenderPort.java`
- Create: `auth/adapter/out/email/MailServiceEmailSenderAdapter.java`
- Create: `auth/configuration/AuthProperties.java`
- Create: `auth/configuration/AuthConfiguration.java` (Clock bean, `@EnableConfigurationProperties(AuthProperties.class)`)
- Modify: `src/main/resources/application.yaml` — add `app.auth.register.*` and `app.auth.email-verification.*` (leave existing `app.auth.jwt/login/bootstrap/cors` untouched — out of scope, unused by the new `AuthProperties` record)
- Modify: `src/main/resources/application-dev.yml` — dev defaults if any override needed (likely none beyond what's already inherited)
- Modify: `src/test/resources/application-test.yml` — add the same new keys with test-friendly values

- [ ] **Step 1: `PasswordHasherPort`** — single method `String hash(String rawPassword)` (do not add `matches`/`needsUpgrade` — nothing calls them in this delivery).
- [ ] **Step 2: `BcryptPasswordHasherAdapter`** — `@Component`, wraps `DelegatingPasswordEncoder("bcrypt", Map.of("bcrypt", new BCryptPasswordEncoder(authProperties.password().bcryptCost())))`. `hash` throws `IllegalArgumentException` for null/empty input (this is a programming-error guard at the adapter boundary, not a business rule — `RegisterService` never calls it with an empty password because `PasswordPolicy.validate` already rejected blank passwords first).
- [ ] **Step 3: `VerificationTokenGeneratorPort`** — `String generate()`. **`SecureVerificationTokenGeneratorAdapter`** — `@Component`, `new SecureRandom()` (instance field, reused — do not `new SecureRandom()` per call), `byte[32] random bytes` → `Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)`.
- [ ] **Step 4: `RegisterThrottlePort`** — `boolean tryConsume(String key, int maxPerHour)`. **`CaffeineRegisterThrottleAdapter`** — `@Component`, one `com.github.benmanes.caffeine.cache.Cache<String, AtomicInteger>` built with `Caffeine.newBuilder().expireAfterWrite(Duration.ofHours(1)).build()`; `tryConsume` does `cache.asMap().computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet() <= maxPerHour` (note: this is a fixed-window-per-first-request counter, per-instance, not cluster-wide — acceptable for this delivery's scope; do not add Redis for this).
- [ ] **Step 5: `EmailSenderPort`** — `void sendVerificationEmail(String toEmail, String displayName, String verificationLink)`. **`MailServiceEmailSenderAdapter`** — `@Component @RequiredArgsConstructor @Slf4j(topic = "AUTH-EMAIL")`, constructor-injects `com.vandunxg.common.email.service.MailService` (confirm exact package/interface name by reading the `common-email` jar sources or `LIBRARY.md` §common-email before wiring — it is `MailService.sendHtmlMail(to, subject, content, String... cc)` per `LIBRARY.md`). Build a minimal inline HTML body (subject + link), call `sendHtmlMail`, catch its checked `MessagingException` and rethrow as an unchecked `RuntimeException` with a log line **that does not include the token or link value** at `error` level (link contains the raw token — never log it; log only `toEmail` masked as `a***@domain`).
- [ ] **Step 6: `AuthProperties`**:
  ```java
  @ConfigurationProperties(prefix = "app.auth")
  public record AuthProperties(Password password, Register register, EmailVerification emailVerification) {
    public record Password(String encoder, int bcryptCost, int minLength, int maxLength) {}
    public record Register(int maxAttemptsPerHour) {}
    public record EmailVerification(Duration tokenTtl, String baseUrl, int resendMaxAttemptsPerHour) {}
  }
  ```
- [ ] **Step 7: `AuthConfiguration`** — `@Configuration @EnableConfigurationProperties(AuthProperties.class)`, one `@Bean Clock clock() { return Clock.systemUTC(); }`.
- [ ] **Step 8: YAML** — under existing `app.auth:` in `application.yaml`, add:
  ```yaml
      register:
        max-attempts-per-hour: ${AUTH_REGISTER_MAX_PER_HOUR:10}
      email-verification:
        token-ttl: ${AUTH_EMAIL_VERIFICATION_TOKEN_TTL:PT24H}
        base-url: ${AUTH_EMAIL_VERIFICATION_BASE_URL:http://localhost:5173/verify-email}
        resend-max-attempts-per-hour: ${AUTH_EMAIL_VERIFICATION_RESEND_MAX_PER_HOUR:10}
  ```
  Mirror the same block (fixed literal values, no env placeholders needed) into `src/test/resources/application-test.yml` under its existing `app.auth:` key.
- [ ] **Step 9: Build**: `./mvnw -DskipTests clean compile`. Green (no tests needed for this task beyond compile — these are thin adapters exercised indirectly by Tasks 4-6's unit tests via mocks, and Task 7's integration test end-to-end).
- [ ] **Step 10: Commit** `feat(auth): add password hasher, token generator, throttle, email sender adapters and AuthProperties`.

## Task 4: RegisterService

**Files:**
- Create: `auth/application/port/in/RegisterUseCase.java`
- Create: `auth/application/command/RegisterCommand.java`
- Create: `auth/application/result/RegisterResult.java`
- Rewrite: `auth/application/service/RegisterService.java` if it exists as a stub, else create it (check first — it may not exist yet; `LoginService.java` is the only stub currently present in `application/service/` and is out of scope, leave it untouched)

**Depends on:** Tasks 1-3 (all ports now have real adapters).

- [ ] **Step 1 (TDD — write first)**: `src/test/java/.../auth/application/service/RegisterServiceTest.java` (Mockito, no Spring context) covering: valid registration returns a `PENDING_VERIFY` result, assigns `OPERATOR`, persists token hash (not raw token), records `USER_REGISTERED` audit, and schedules the email send after commit; duplicate username → `AuthDomainException(USERNAME_ALREADY_EXISTS)` from the pre-check; duplicate email → `EMAIL_ALREADY_EXISTS`; concurrent-race duplicate (pre-check passes but `userRepositoryPort.save` throws `DataIntegrityViolationException`) → translated to `USERNAME_ALREADY_EXISTS` or `EMAIL_ALREADY_EXISTS` (inspect the exception's root cause message for which unique index name fired — `auth_users_normalized_username_uk` vs `auth_users_normalized_email_uk` — default to username if unparseable); password policy violation → `PASSWORD_POLICY_VIOLATION`, and the mock password hasher / token generator are **never invoked** in this case (prove no wasted work / no accidental hash-before-validate ordering bug); throttle exceeded → `AUTH_RATE_LIMITED` before any other check runs (i.e. throttle is the very first gate).
- [ ] **Step 2: `RegisterUseCase`** — `RegisterResult register(RegisterCommand command)`.
- [ ] **Step 3: `RegisterCommand`** — `username, email, displayName, password, ipAddress` (plain getters, no `Request` base — this is the application layer, not a web DTO).
- [ ] **Step 4: `RegisterResult`** — `id, username, email, displayName, status` (a static `from(User)` factory is fine, keep it a plain value holder, no behavior).
- [ ] **Step 5: `RegisterService`** implements the flow in this exact order (order matters — the tests above assert it): (1) `throttlePort.tryConsume("register:" + ip, authProperties.register().maxAttemptsPerHour())` → else `AUTH_RATE_LIMITED`; (2) normalize username/email via `User.normalize(...)`; (3) `passwordPolicy.validate(...)` → else `PASSWORD_POLICY_VIOLATION`; (4) pre-check `existsByNormalizedUsername`/`existsByNormalizedEmail` → else the matching `*_ALREADY_EXISTS`; (5) `roleRepositoryPort.findByCode("OPERATOR")` → else `INVALID_ROLE` (should not happen given the seed migration, but the domain factory already guards it — surface the same error if the port ever returns empty); (6) `passwordHasherPort.hash(...)`; (7) `User.register(...)`; (8) `userRepositoryPort.save(user)` wrapped in try/catch translating `DataIntegrityViolationException` to the duplicate error codes described in Step 1; (9) `userRoleRepositoryPort.save(new UserRole(saved.getId(), operatorRole.getId()))`; (10) generate raw token + SHA-256 hash it (`HashUtils.sha256`) + SHA-256-hash the IP the same way; (11) `EmailVerificationToken.issue(IdUtils.nextId(), saved.getId(), tokenHash, now, authProperties.emailVerification().tokenTtl(), ipHash)` then `tokenRepositoryPort.save(...)`; (12) `auditLogPort.record(...)` with `operation = USER_REGISTERED`, `domain = AUTH`, `objectId = saved.getId()`, `changedBy = saved.getId()`, `ipAddress = ipHash` (the hash, never the raw IP), `data = null` (no PII in audit metadata beyond what the row itself already needs); (13) register a `TransactionSynchronization.afterCommit` (via `TransactionSynchronizationManager.registerSynchronization`, only if `TransactionSynchronizationManager.isSynchronizationActive()`) that calls `emailSenderPort.sendVerificationEmail(saved.getEmail(), saved.getDisplayName(), authProperties.emailVerification().baseUrl() + "?token=" + rawToken)`, catching and logging (not rethrowing — a failed send must not undo the committed registration, per the design doc) any exception **without logging the link or raw token**; (14) return `RegisterResult.from(saved)`. The whole method (steps 2-12) is `@Transactional`; step 13's callback necessarily runs after that transaction commits.
- [ ] **Step 6: Run** `./mvnw -Dtest=RegisterServiceTest test`. Green.
- [ ] **Step 7: Commit** `feat(auth): add RegisterService orchestrating registration transaction and post-commit email`.

## Task 5: VerifyEmailService

**Files:**
- Create: `auth/application/port/in/VerifyEmailUseCase.java`
- Create: `auth/application/command/VerifyEmailCommand.java`
- Create: `auth/application/service/VerifyEmailService.java`
- Reuse `RegisterResult` as the return type (rename consideration: it's generic enough — `{id, username, email, displayName, status}` — do not create a near-duplicate `VerifyEmailResult`; if the reviewer disagrees this is a judgment call to flag, not silently diverge on)

**Depends on:** Tasks 1-3.

- [ ] **Step 1 (TDD)**: `VerifyEmailServiceTest` covering: valid token activates the user (`PENDING_VERIFY` → `ACTIVE`), consumes the token (second call with the same raw token fails), records `EMAIL_VERIFIED` audit; unknown token hash (no row) → `AuthDomainException(EMAIL_VERIFICATION_TOKEN_INVALID)`; expired token → same error (via the domain's own `consume()` check — the service must not duplicate that expiry logic, just let `token.consume(now)` throw); already-used token → same error.
- [ ] **Step 2: `VerifyEmailUseCase`** — `RegisterResult verifyEmail(VerifyEmailCommand command)`.
- [ ] **Step 3: `VerifyEmailCommand`** — single field `token` (the raw opaque token from the client).
- [ ] **Step 4: `VerifyEmailService`**, `@Transactional`: (1) SHA-256 the raw token (`HashUtils.sha256`); (2) `tokenRepositoryPort.findByTokenHashForUpdate(hash)` → else `EMAIL_VERIFICATION_TOKEN_INVALID` (log at `warn`, do not log the raw token or its hash — an identifier-free "unknown token presented" line is enough, optionally with a truncated/partial hash prefix for correlation if you want, but not required); (3) `token.consume(now)` (throws the same error if expired/used — do not catch and re-wrap, let it propagate); (4) `tokenRepositoryPort.save(token)`; (5) `userRepositoryPort.findById(token.getUserId())` → else the same invalid-token error (should be unreachable given the FK, but do not throw an unchecked NPE if it somehow is); (6) `user.verifyEmail(now)`; (7) `userRepositoryPort.save(user)`; (8) `auditLogPort.record(...)` with `operation = EMAIL_VERIFIED, domain = AUTH, objectId = user.getId(), changedBy = user.getId()`; (9) return `RegisterResult.from(saved)`.
- [ ] **Step 5: Run** `./mvnw -Dtest=VerifyEmailServiceTest test`. Green.
- [ ] **Step 6: Commit** `feat(auth): add VerifyEmailService with locked one-time token consumption`.

## Task 6: ResendVerificationEmailService

**Files:**
- Create: `auth/application/port/in/ResendVerificationEmailUseCase.java`
- Create: `auth/application/command/ResendVerificationEmailCommand.java`
- Create: `auth/application/service/ResendVerificationEmailService.java`

**Depends on:** Tasks 1-3.

- [ ] **Step 1 (TDD)**: `ResendVerificationEmailServiceTest` covering: unknown identifier → method returns normally (void), **no** token is created, **no** email is sent, **no** audit is recorded (assert zero interactions with those three ports) — this is the enumeration-safety contract, test it by asserting silence, not by asserting a specific "not found" path; already-`ACTIVE` account → same silent no-op; valid `PENDING_VERIFY` account → old unused tokens invalidated, a new token issued and saved, `EMAIL_VERIFICATION_REQUESTED` audit recorded, email send scheduled after commit; throttle exceeded → `AuthDomainException(AUTH_RATE_LIMITED)` thrown **before** the identifier lookup (this is the one path that *does* throw, and it throws identically regardless of whether the identifier would have resolved — so it leaks no information about account existence either).
- [ ] **Step 2: `ResendVerificationEmailUseCase`** — `void resend(ResendVerificationEmailCommand command)` (void — the controller's response is identical for every outcome except throttling, so there is nothing for the use case to return).
- [ ] **Step 3: `ResendVerificationEmailCommand`** — `identifier, ipAddress`.
- [ ] **Step 4: `ResendVerificationEmailService`**, `@Transactional`: (1) throttle check first, same pattern as `RegisterService`, key `"resend:" + ip`, limit `authProperties.emailVerification().resendMaxAttemptsPerHour()`; (2) `userRepositoryPort.findByNormalizedIdentifier(User.normalize(identifier))`; (3) if empty **or** `!user.isPendingVerify()` → `log.info` a single enumeration-safe line (no identifier value, just `"[resend] no-op"`) and return; (4) else: `tokenRepositoryPort.invalidateAllForUser(user.getId(), now)`; generate+hash a new token the same way as `RegisterService`; `EmailVerificationToken.issue(...)`; `tokenRepositoryPort.save(...)`; `auditLogPort.record(...)` with `operation = EMAIL_VERIFICATION_REQUESTED`; register the same `afterCommit` email-send pattern as `RegisterService` (do not duplicate the boilerplate differently — same shape).
- [ ] **Step 5: Run** `./mvnw -Dtest=ResendVerificationEmailServiceTest test`. Green.
- [ ] **Step 6: Commit** `feat(auth): add ResendVerificationEmailService with enumeration-safe response`.

## Task 7: Web layer — DTOs, mapper, controller, security config

**Files:**
- Create: `auth/adapter/in/web/dto/request/RegisterRequest.java`
- Create: `auth/adapter/in/web/dto/request/VerifyEmailRequest.java`
- Create: `auth/adapter/in/web/dto/request/ResendVerificationRequest.java`
- Create: `auth/adapter/in/web/dto/response/RegisterResponse.java`
- Create: `auth/adapter/in/web/mapper/AuthWebMapper.java`
- Rewrite: `auth/adapter/in/web/AuthController.java` (currently `public class AuthController {}`)
- Modify: `configuration/security/SecurityConfiguration.java`
- Create test: `src/test/java/com/vandunxg/file_processing/auth/adapter/in/web/AuthControllerIT.java`

**Depends on:** Tasks 4-6.

- [ ] **Step 1: `RegisterRequest`** — `username (@NotBlank @Size(min=3,max=64))`, `email (@NotBlank @Email @Size(max=254))`, `displayName (@NotBlank @Size(min=2,max=150))`, `password (@NotBlank)` (length/policy enforced by `PasswordPolicy` in the service, returning `422` — do not duplicate with `@Size` since Jakarta counts UTF-16 units, not the code points the domain policy uses). Add `@Schema` description/example on each field per `RULE.md §14.3`.
- [ ] **Step 2: `VerifyEmailRequest`** — `token (@NotBlank)`.
- [ ] **Step 3: `ResendVerificationRequest`** — `identifier (@NotBlank)`.
- [ ] **Step 4: `RegisterResponse`** — `id (UUID), username, email, displayName, status (String)`. Never include password/token/hash.
- [ ] **Step 5: `AuthWebMapper`** (`@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)`): `RegisterCommand toCommand(RegisterRequest request, String ipAddress)`; `VerifyEmailCommand toCommand(VerifyEmailRequest request)`; `ResendVerificationEmailCommand toCommand(ResendVerificationRequest request, String ipAddress)`; `RegisterResponse toResponse(RegisterResult result)`.
- [ ] **Step 6: `AuthController`** — `@RestController @RequestMapping("${app.api.prefix}/${app.api.version}/auth") @RequiredArgsConstructor @Slf4j(topic = "AUTH-CONTROLLER")`. Before wiring IP extraction, check whether `com.vandunxg.common.web.support.IpUtils` (or similar, per `LIBRARY.md` "servlet-request IP extraction") exposes a ready-made "get client IP from `HttpServletRequest`" method (search with codegraph/grep first) — use it if present (handles `X-Forwarded-For` correctly behind a proxy); fall back to `request.getRemoteAddr()` only if no such helper exists. Three endpoints:
  ```java
  @Operation(summary = "Register a new operator account")
  @SecurityRequirements // public
  @PostMapping("/register")
  @ResponseStatus(HttpStatus.CREATED)
  public Response<RegisterResponse> register(@Valid @RequestBody RegisterRequest request, HttpServletRequest http) {
    log.info("[register] username={} ip={}", request.getUsername(), maskedIp);
    var command = webMapper.toCommand(request, clientIp(http));
    var result = registerUseCase.register(command);
    return Response.of(webMapper.toResponse(result));
  }

  @Operation(summary = "Verify an email address using an opaque token")
  @SecurityRequirements
  @PostMapping("/verify-email")
  public Response<RegisterResponse> verifyEmail(@Valid @RequestBody VerifyEmailRequest request) {
    var result = verifyEmailUseCase.verifyEmail(webMapper.toCommand(request));
    return Response.of(webMapper.toResponse(result));
  }

  @Operation(summary = "Resend a verification email (enumeration-safe)")
  @SecurityRequirements
  @PostMapping("/resend-verification")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void resendVerification(@Valid @RequestBody ResendVerificationRequest request, HttpServletRequest http) {
    resendVerificationEmailUseCase.resend(webMapper.toCommand(request, clientIp(http)));
  }
  ```
  Confirm `com.vandunxg.common.models.dto.response.Response<T>` is the right wrapper (per `RULE.md §13`) and that returning it with `@ResponseStatus(CREATED)` on the method still yields `201` (Spring honors `@ResponseStatus` regardless of the wrapper body type). Do **not** put any business logic here beyond DTO↔command mapping and IP extraction.
- [ ] **Step 7: `SecurityConfiguration`** — `PUBLIC_URLS` currently includes the blanket `"/api/v1/**"`, which makes every future business endpoint public — this is a real bug, fix it now. Remove `"/api/v1/**"` from `PUBLIC_URLS` and add the three concrete paths: `"/api/v1/auth/register"`, `"/api/v1/auth/verify-email"`, `"/api/v1/auth/resend-verification"`. Leave every other existing entry (`/`, `/health`, `/ready`, `/ws/**`, `/api/public/**`, the JWKS/refresh-token/register placeholders) untouched — they are out of this task's scope even though some look stale; do not "clean them up" beyond removing the one blanket entry that this feature's own endpoints would otherwise have hidden behind. `webSecurityCustomizer()` mirrors the same `PUBLIC_URLS` array so it stays consistent automatically.
- [ ] **Step 8: `AuthControllerIT`** (`@PostgresIntegrationTest` + `@AutoConfigureMockMvc` or a `TestRestTemplate`, whichever the existing test infra already supports — check `PostgresIntegrationTest`'s `@SpringBootTest` webEnvironment setting) covering the full contract table from Global Constraints: `201` on valid register; `409` on duplicate username and on duplicate email (two separate cases); `422` on a policy-violating password; `429` after exceeding the configured per-hour limit (lower the test profile's `app.auth.register.max-attempts-per-hour` to something small like `3` in `application-test.yml` specifically for fast IT coverage, or issue >10 requests — prefer lowering the config); `200` + `ACTIVE` status on a valid verify-email call chained after a real register call (extract the raw token — since the controller never returns it, the test must read it out of band: either query the DB directly for the token hash and can't reverse it, **or** have the test use a test-only email sender adapter that captures the last sent link (`@Primary` test bean overriding `EmailSenderPort`) so the IT can pull the raw token from the captured verification link — implement this test double rather than trying to guess/reverse the hash); `410` on a syntactically-plausible but unknown token; `204` always from resend-verification, for both an unknown identifier and a real pending one. **Use a distinct fake IP (e.g. via a custom `RemoteAddr` per test, or `@BeforeEach` varying a header) per test method** so the shared Caffeine throttle state (same JVM across the test class) doesn't make unrelated tests fail from cross-test rate-limit bleed — except the dedicated `429` test, which deliberately reuses one IP.
- [ ] **Step 9: Run** `./mvnw -Dtest=AuthControllerIT test`. Green.
- [ ] **Step 10: Commit** `feat(auth): expose register/verify-email/resend-verification endpoints and restrict public URLs`.

## Task 8: Final verification pass

**Files:** none new — review only.

- [ ] **Step 1**: `./mvnw spotless:apply`.
- [ ] **Step 2**: `./mvnw verify` (full suite, Testcontainers required — Docker must be available).
- [ ] **Step 3**: `git diff --stat` and a targeted `grep -rn` across the new/changed files for the literal words `password`, `token`, `rawToken` immediately inside any `log.` call — manually confirm none of them log a raw secret value (masked email/IP hashes are fine; the raw opaque token and raw password must never appear in any `log.*` call, audit `data`, or HTTP response body).
- [ ] **Step 4**: Confirm every new `AuthErrorCode` reference (there should be none new — this plan intentionally reuses the existing enum) still resolves in both `messages.properties` and `messages_vi.properties`.
- [ ] **Step 5**: Commit any final formatting-only correction separately (`style: spotless apply`) if Step 1 changed anything beyond what the task commits already captured.
