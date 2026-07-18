# Auth Register Infra Hardening — Redis Rate Limit, Redis-only Verification Token, RabbitMQ Event-Driven Audit/Email

**Goal:** Replace three infra shortcuts accepted in the original register/verify-email/resend-verification delivery (`docs/superpowers/specs/2026-07-17-auth-register-design.md`) with production-grade equivalents, without changing the domain model or the three application services' business logic where avoidable:

1. `RegisterThrottlePort` moves from per-instance Caffeine to cluster-wide Redis, using a sliding-window-counter algorithm.
2. `EmailVerificationTokenRepositoryPort` moves from PostgreSQL to Redis-only storage (no DB row at all going forward).
3. Audit-log recording and verification-email sending move from direct synchronous port calls inside the application services to RabbitMQ-published events, consumed by an in-process `@RabbitListener` that then calls the same, unchanged outbound ports (`AuditLogPort`, `EmailSenderPort`).

**Scope:** Only `RegisterService`, `VerifyEmailService`, `ResendVerificationEmailService` and their outbound adapters are touched. Login, JWT, RBAC admin, and any other not-yet-built auth capability are out of scope — this is an infra swap under already-shipped functionality, not new business behavior.

**Non-goals:** No change to `EmailVerificationToken`, `User`, `AuditLog` domain classes. No change to the three services' *call order* or business rules (throttle → validate → duplicate-check → persist → issue token → audit → email, all as already implemented). No new HTTP endpoint or DTO changes. The existing `auth_email_verification_tokens` Postgres table and its Flyway migration stay on disk, unused, per explicit decision below.

## Architecture

Hexagonal boundaries stay intact: only adapters are added/replaced, ports are reused wherever their existing shape already fits.

```
auth/
├── domain/
│   └── event/                          # NEW — plain payload records for AMQP, zero Spring/JPA imports
│       └── SendVerificationEmailEvent.java
├── application/
│   └── port/out/
│       ├── RegisterThrottlePort.java              # UNCHANGED signature
│       ├── EmailVerificationTokenRepositoryPort.java  # UNCHANGED signature
│       ├── AuditLogPort.java                       # UNCHANGED — now only called from adapter/in/amqp
│       ├── EmailSenderPort.java                    # UNCHANGED — now only called from adapter/in/amqp
│       ├── AuditLogEventPublisherPort.java          # NEW
│       └── VerificationEmailEventPublisherPort.java # NEW
├── adapter/
│   ├── in/
│   │   └── amqp/                       # NEW package — inbound AMQP consumers
│   │       ├── AuditLogEventListener.java
│   │       └── VerificationEmailEventListener.java
│   └── out/
│       ├── cache/
│       │   ├── RedisRegisterThrottleAdapter.java          # replaces CaffeineRegisterThrottleAdapter
│       │   └── RedisEmailVerificationTokenAdapter.java     # replaces EmailVerificationTokenPersistenceAdapter
│       ├── amqp/                        # NEW package — outbound publishers
│       │   ├── RabbitAuditLogEventPublisherAdapter.java
│       │   └── RabbitVerificationEmailEventPublisherAdapter.java
│       ├── persistence/                 # AuditLogPersistenceAdapter unchanged; EmailVerificationToken* files deleted
│       └── email/                       # MailServiceEmailSenderAdapter unchanged
└── configuration/
    ├── AuthProperties.java              # extended with redis + amqp nested records
    ├── AuthRedisConfiguration.java      # NEW — RedisScript beans
    └── AuthAmqpConfiguration.java       # NEW — exchange/queue/binding/retry topology
```

Flow after this change, for `RegisterService.register()` (identical shape for the other two services):

1. `throttlePort.tryConsume(...)` — now Redis-backed, same call site.
2. Steps 2-11 unchanged (validate, duplicate-check, persist user/role, issue token) — the only adapter swap here is `EmailVerificationTokenRepositoryPort`'s implementation.
3. Inside the existing `afterCommit` `TransactionSynchronization` block: instead of calling `auditLogPort.record(...)` synchronously pre-commit and `emailSenderPort.sendVerificationEmail(...)` post-commit, the service calls `auditLogEventPublisherPort.publish(auditLog)` and `verificationEmailEventPublisherPort.publish(toEmail, displayName, link)` — both post-commit, both fire-and-forget from the service's perspective (a failed publish is logged, never rethrown, never rolls back the already-committed registration — same failure posture as today).
4. RabbitMQ delivers each message to its queue; `AuditLogEventListener`/`VerificationEmailEventListener` (running in this same Spring Boot process) consume it near-immediately and call the same `AuditLogPort.record(...)` / `EmailSenderPort.sendVerificationEmail(...)` that the service used to call directly.

## A. Rate limiting — Redis Sliding Window Counter

`RegisterThrottlePort` keeps its exact signature:

```java
public interface RegisterThrottlePort {
  boolean tryConsume(String key, int maxPerHour);
}
```

`RedisRegisterThrottleAdapter` (`@Component`, replaces `CaffeineRegisterThrottleAdapter` — delete that file) executes one atomic Lua script per call via `StringRedisTemplate.execute(RedisScript<Long>, List.of(redisKey), String.valueOf(maxPerHour), "3600")`. Redis key = `{app.auth.redis.throttle.key-prefix}{key}` (the `key` argument is the same `"register:" + ip` / `"resend:" + ip` string the services already build — no call-site change).

`src/main/resources/scripts/sliding-window-rate-limiter.lua`:

```lua
-- KEYS[1] = base key (prefix + caller key, without window suffix)
-- ARGV[1] = limit (max per window)
-- ARGV[2] = window size in seconds
local base = KEYS[1]
local limit = tonumber(ARGV[1])
local windowSize = tonumber(ARGV[2])

local time = redis.call('TIME')
local nowSeconds = tonumber(time[1])

local currentIdx = math.floor(nowSeconds / windowSize)
local prevIdx = currentIdx - 1
local elapsedFraction = (nowSeconds - (currentIdx * windowSize)) / windowSize

local currentKey = base .. ':' .. currentIdx
local prevKey = base .. ':' .. prevIdx

local currentCount = tonumber(redis.call('GET', currentKey) or '0')
local prevCount = tonumber(redis.call('GET', prevKey) or '0')

local weighted = (prevCount * (1 - elapsedFraction)) + currentCount

if weighted + 1 > limit then
  return 0
end

local newVal = redis.call('INCR', currentKey)
if newVal == 1 then
  redis.call('EXPIRE', currentKey, windowSize * 2)
end

return 1
```

`redis.call('TIME')` is used instead of a client-supplied timestamp so all app instances agree on the same clock (the Redis server's), which matters once this runs behind more than one instance. Rationale for sliding-window-counter over the two alternatives declined during brainstorming: fixed-window (simpler, matches the original SRS's "Redis atomic INCR adapter" wording) allows up to ~2x the limit at a window boundary; token bucket is built for burst-shaping API traffic, not for capping abuse — sliding-window-counter is the accepted middle ground (O(1) memory, no boundary burst) and is what this design uses.

`RedisScript<Long>` is loaded once as a `@Bean` in `AuthRedisConfiguration` from the classpath resource above (`DefaultRedisScript`, result type `Long`).

## B. Email verification token — Redis-only storage

`EmailVerificationTokenRepositoryPort` keeps its exact signature; `EmailVerificationToken` domain class is untouched. `RedisEmailVerificationTokenAdapter` replaces `EmailVerificationTokenPersistenceAdapter`.

Two Redis keys per active token:

- `{token-key-prefix}{tokenHash}` → JSON `{id, userId, issuedAt, expiresAt, ipAddressHash}`, TTL = `Duration.between(now, token.getExpiresAt())`.
- `{user-key-prefix}{userId}` → `tokenHash` (pointer to the user's current token), same TTL.

Adapter method behavior:

- **`save(token)`**: if `token.getUsedAt() != null`, no-op and return the token as-is — the token was already atomically removed from Redis by `findByTokenHashForUpdate`'s `GETDEL`, so there is nothing left to persist. This is the one non-obvious piece of adapter behavior and gets a one-line comment explaining why. Otherwise (fresh issuance): `SET` both keys with the computed TTL.
- **`findByTokenHashForUpdate(tokenHash)`**: `stringRedisTemplate.opsForValue().getAndDelete(tokenKey)` — Redis's native `GETDEL`, atomic fetch-and-remove, which *is* the one-time-use enforcement (a second concurrent call with the same raw token gets `null` → `Optional.empty()` → the existing `AuthDomainException(EMAIL_VERIFICATION_TOKEN_INVALID)` path, unchanged in `VerifyEmailService`). No explicit lock is needed because Redis commands are already atomic; the previous JPA `PESSIMISTIC_WRITE` semantics are replaced by this destructive-read, not by a lock primitive.
- **`invalidateAllForUser(userId, now)`**: one Lua script — `GET` the user pointer key; if present, `DEL` both the pointed-at token key and the pointer key itself. If absent, no-op. (`now` parameter is unused by the Redis implementation — TTL already handles expiry — kept only because it's part of the existing port signature.)

Both `save` and `invalidateAllForUser` remain two separate port calls exactly as `ResendVerificationEmailService` already invokes them (invalidate-then-save); each individual call is atomic, the pair is not — an acceptable, unchanged-in-practice risk window given the original JPA version's atomicity came from wrapping both in one DB transaction, and the actual business exposure (a very-low-traffic resend racing itself) is negligible.

Deleted (dead code once the Redis adapter lands): `EmailVerificationTokenPersistenceAdapter.java`, `JpaEmailVerificationTokenRepository.java`, `EmailVerificationTokenEntity.java`, `EmailVerificationTokenPersistenceMapper.java`, and the corresponding assertions in `MigrationAndSeedIT`/the whole `EmailVerificationTokenPersistenceAdapterIT` file. The Flyway migration that created `auth_email_verification_tokens` and the physical table stay untouched, per explicit decision — no new migration, no `DROP TABLE`.

## C. Audit log + verification email — RabbitMQ, consumed in-process

New outbound ports:

```java
public interface AuditLogEventPublisherPort {
  void publish(AuditLog auditLog);
}

public interface VerificationEmailEventPublisherPort {
  void publish(String toEmail, String displayName, String verificationLink);
}
```

`domain/event/SendVerificationEmailEvent.java` (plain record, no Spring/JPA imports):

```java
public record SendVerificationEmailEvent(String toEmail, String displayName, String verificationLink) {}
```

The audit event reuses the existing `AuditLog` domain object as its wire payload directly (no duplicate DTO) — `common-amqp`'s `JacksonJsonMessageConverter` trusts the `com.vandunxg` package tree, which covers it.

Publisher adapters (`adapter/out/amqp/`) wrap `com.vandunxg.common.amqp.publisher.AmqpEventPublisher.publish(MessageRoute, payload)` (fire-and-forget, `CompletableFuture<Void>`); each `.exceptionally(...)` just logs a warning with the identifying id (never the verification link, which carries the raw token).

`RegisterService`, `VerifyEmailService`, `ResendVerificationEmailService`: remove their direct `AuditLogPort`/`EmailSenderPort` dependencies, replace with `AuditLogEventPublisherPort`/`VerificationEmailEventPublisherPort`. The audit-log publish moves into the same `afterCommit` synchronization block the email send already uses (today `VerifyEmailService` records audit pre-commit, inside the transaction — this now also becomes a post-commit publish, for consistency across all three services and so no event fires for a transaction that then rolls back).

Inbound listeners (`adapter/in/amqp/`), each still constructor-injecting the *original*, unchanged port:

```java
@Component @RequiredArgsConstructor @Slf4j(topic = "AUTH-AUDIT-LOG-LISTENER")
public class AuditLogEventListener {
  private final AuditLogPort auditLogPort;

  @RabbitListener(queues = "${app.auth.amqp.queue.audit-log}")
  public void onAuditLogEvent(AuditLog auditLog) {
    auditLogPort.record(auditLog);
  }
}

@Component @RequiredArgsConstructor @Slf4j(topic = "AUTH-EMAIL-LISTENER")
public class VerificationEmailEventListener {
  private final EmailSenderPort emailSenderPort;

  @RabbitListener(queues = "${app.auth.amqp.queue.verification-email}")
  public void onSendVerificationEmailEvent(SendVerificationEmailEvent event) {
    emailSenderPort.sendVerificationEmail(event.toEmail(), event.displayName(), event.verificationLink());
  }
}
```

Topology (`AuthAmqpConfiguration`, all names read from `AuthProperties.amqp()`):

- One durable `TopicExchange` (`app.auth.amqp.exchange`) plus one dead-letter `TopicExchange` (`{exchange}.dlx`).
- Two durable queues (`app.auth.amqp.queue.audit-log`, `.verification-email`), each declared with `x-dead-letter-exchange` pointing at the DLX and `x-dead-letter-routing-key` matching its own routing key (via `common-amqp`'s `QueueOptions` constants).
- Two matching DLQs (`{queue}.dlq`), bound to the DLX with the same routing keys.
- `SimpleRabbitListenerContainerFactory` with a stateless retry advice chain (max 3 attempts, exponential backoff 1s→10s) ending in `RejectAndDontRequeueRecoverer`, so a message that keeps failing is dead-lettered (preserved in the DLQ for investigation/replay) instead of being lost or retried forever — this is the concrete mechanism satisfying "avoid losing events."

`spring.rabbitmq.enabled: true` is required for `common-amqp`'s `AmqpAutoConfiguration` to activate at all (it is a no-op otherwise, per `LIBRARY.md`).

## Configuration

All new keys under `app.auth.*`, mirroring the existing `AuthProperties` record shape:

```java
@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(
    Password password, Register register, EmailVerification emailVerification,
    Redis redis, Amqp amqp) {

  public record Password(String encoder, int bcryptCost, int minLength, int maxLength) {}
  public record Register(int maxAttemptsPerHour) {}
  public record EmailVerification(Duration tokenTtl, String baseUrl, int resendMaxAttemptsPerHour) {}

  public record Redis(Throttle throttle, EmailVerificationKeys emailVerification) {
    public record Throttle(String keyPrefix) {}
    public record EmailVerificationKeys(String tokenKeyPrefix, String userKeyPrefix) {}
  }

  public record Amqp(String exchange, RoutingKey routingKey, Queue queue) {
    public record RoutingKey(String auditLog, String verificationEmail) {}
    public record Queue(String auditLog, String verificationEmail) {}
  }
}
```

`application.yaml` additions:

```yaml
app:
  auth:
    redis:
      throttle:
        key-prefix: ${AUTH_THROTTLE_REDIS_PREFIX:auth:throttle:}
      email-verification:
        token-key-prefix: ${AUTH_EMAIL_VERIFY_TOKEN_PREFIX:auth:email-verify:token:}
        user-key-prefix: ${AUTH_EMAIL_VERIFY_USER_PREFIX:auth:email-verify:user:}
    amqp:
      exchange: ${AUTH_AMQP_EXCHANGE:auth.events}
      routing-key:
        audit-log: ${AUTH_AMQP_ROUTING_AUDIT_LOG:auth.audit-log.recorded}
        verification-email: ${AUTH_AMQP_ROUTING_VERIFY_EMAIL:auth.email.verification-requested}
      queue:
        audit-log: ${AUTH_AMQP_QUEUE_AUDIT_LOG:auth.audit-log.queue}
        verification-email: ${AUTH_AMQP_QUEUE_VERIFY_EMAIL:auth.email-verification.queue}

spring:
  rabbitmq:
    enabled: true
    host: ${RABBITMQ_HOST:localhost}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_USERNAME:rabbitmq_user}
    password: ${RABBITMQ_PASSWORD:rabbitmq_password}
```

The default username/password/ports already match the `rabbitmq` service the `docker-compose.yml` working-tree change defines. `.env.example` gains the corresponding `RABBITMQ_*`, `AUTH_THROTTLE_REDIS_PREFIX`, `AUTH_EMAIL_VERIFY_*`, `AUTH_AMQP_*` entries. `application-test.yml` gets the same `app.auth.redis`/`app.auth.amqp` block (fixed literal test values, no env placeholders) plus `spring.rabbitmq.enabled: true` pointed at whatever Testcontainers RabbitMQ instance the integration tests bring up (see Testing).

## Security decisions

- The Redis-stored verification token JSON never includes the raw opaque token, only its SHA-256 hash (used as the Redis key itself) — same guarantee as the current DB column.
- `SendVerificationEmailEvent.verificationLink` carries the raw token exactly as today's post-commit direct call does; it travels over the RabbitMQ connection (not logged, not persisted by any listener) exactly like it currently travels over the SMTP connection — no new exposure surface beyond "one more transport hop within the same private infra."
- Rate-limit and token Redis keys are namespaced (`auth:throttle:`, `auth:email-verify:...`) and configurable, so they cannot collide with unrelated cache usage sharing the same Redis instance.
- DLQ messages retain the same payload (an `AuditLog` or `SendVerificationEmailEvent`) — nothing new is exposed by dead-lettering that wasn't already in the original message.

## Testing

- `RedisRegisterThrottleAdapterIT` (Testcontainers Redis, or reuse whatever Redis Testcontainers setup already backs other Redis-dependent tests if one exists): asserts allow up to the limit, deny beyond it, weighted decay across a window boundary (advance time via a controllable clock is not possible against `redis.call('TIME')` — instead assert the boundary behavior indirectly: burst to the limit, wait past the window, confirm requests are allowed again).
- `RedisEmailVerificationTokenAdapterIT` (Testcontainers Redis): save-then-find round-trip; `findByTokenHashForUpdate` is destructive (second call returns empty); `invalidateAllForUser` removes both keys; TTL expiry (short TTL + wait, or a Redis `PEXPIRE`/`PTTL` assertion rather than a real sleep where avoidable).
- `RegisterService`/`VerifyEmailService`/`ResendVerificationEmailService` unit tests: replace `AuditLogPort`/`EmailSenderPort` mocks with `AuditLogEventPublisherPort`/`VerificationEmailEventPublisherPort` mocks, same interaction assertions (called once, after commit, with the right arguments) — the fake-transaction-synchronization test harness these already use does not change shape.
- New `AuditLogEventListenerTest`/`VerificationEmailEventListenerTest` (plain Mockito, no Spring context): listener delegates verbatim to the injected port.
- `AuthControllerIT`: needs a RabbitMQ Testcontainer added alongside the existing PostgreSQL one (check `PostgresTestContainerBase` — likely add a sibling `RabbitMqTestContainerBase` or extend the base to also start RabbitMQ) so `@RabbitListener` beans have a broker to connect to; assert end-to-end that a register call still results in an audit row and a captured verification email (same test-double `EmailSenderPort` pattern already planned in the original delivery), tolerating a short poll/await for the async hop instead of asserting immediately after the HTTP response returns.

## Rollout notes

- `CaffeineRegisterThrottleAdapter` is deleted outright (no feature flag, no dual-write) — Caffeine was always documented as a placeholder ("acceptable for this delivery's scope; not backed by Redis").
- The existing `auth_email_verification_tokens` table keeps existing rows (if any) stranded — acceptable, this is a dev-stage feature with negligible real data; no backfill into Redis is planned or needed.
- `spring.rabbitmq.enabled: true` must ship together with the RabbitMQ connection settings — a partial rollout (code deployed, RabbitMQ not reachable) would fail application startup once `@RabbitListener` beans exist and cannot connect, so this must go out as one deploy alongside a running RabbitMQ instance, not staged.

## Acceptance Criteria

- Register/resend requests are throttled cluster-wide (two application instances sharing one Redis observe the same counter), using the sliding-window algorithm above.
- A restart of the application does not reset in-flight rate-limit counters (they survive in Redis, unlike the previous Caffeine cache).
- No row is ever written to `auth_email_verification_tokens` after this change ships; verification tokens are fully readable/writable only through Redis, and remain single-use and TTL-bound.
- Registering, verifying, and resending each still produce exactly one audit log row and (at most) one attempted verification email — now via RabbitMQ — with the same content as before.
- Killing RabbitMQ mid-flight does not fail the register/verify/resend HTTP call itself (publish failures are logged, not rethrown); a message that fails processing 3 times lands in its DLQ rather than disappearing.
