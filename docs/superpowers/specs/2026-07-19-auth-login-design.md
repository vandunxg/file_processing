# Auth Login Design

**Goal:** Deliver password-based login, refresh-token rotation, server-side session tracking, and self-service session revocation as the second slice of the `auth` module, building on the register/verify-email foundation.

**Scope in:** `POST /login`, `POST /refresh`, `POST /logout`, `POST /sessions/revoke-all`, `GET /me`, `GET /sessions`, `DELETE /sessions/{sid}`. Wires the JWT resource server to accept the tokens this feature issues and reject revoked ones.

**Scope out:** Password-change / reset flows (a later spec calls `RevokeAllSessionsUseCase` from here). MFA / 2FA. Admin session management. OAuth social login. Session retention cleanup (table is created; scheduler is a later story).

## Architecture

- **Domain:** New aggregate `Session` (sid, userId, credentialVersionSnapshot, refreshTokenHash, userAgent, ipAddressHash, createdAt, lastUsedAt, expiresAt, revokedAt, revokedReason, deletedAt). Domain behavior methods: `Session.issue`, `session.rotateRefresh`, `session.revoke`, `session.isActive`. `User` gains `registerFailedLogin(now, max, lockDuration)`, `resetFailedLogin()`, `bumpCredentialVersion()`, and `isLocked(now)` (the current no-arg overload becomes `isLocked(Instant.now(clock))`-friendly via the injected `Clock`).
- **Application:** Seven use cases (`LoginUseCase`, `RefreshTokenUseCase`, `LogoutUseCase`, `RevokeAllSessionsUseCase`, `RevokeSessionUseCase`, `ListSessionsUseCase`, `GetCurrentUserUseCase`) each with a matching `@Service`. `RevokeAllSessionsUseCase` is also the public seam the future password-change flow calls into. `RegisterThrottlePort` is generalized to `AuthThrottlePort` with signature `tryConsume(String key, int max, Duration window)`; `RegisterService` passes 1h explicitly.
- **Session persistence:** Redis is the hot path; Postgres is durable + admin-audit. `SessionRepositoryPort.save` writes Redis synchronously and publishes a `SessionPersistEvent` via RabbitMQ; a `SessionEventListener` writes Postgres in a listener transaction. `revoke` / `rotateRefresh` follow the same publish-after-Redis pattern. Read fallback goes Redis → Postgres → 404.
- **Access token:** RS256 JWT, 15 min TTL. Claims: `iss`, `aud`, `sub` (userId), `sid` (sessionId), `cv` (credentialVersion snapshot), `roles` (role code array), `iat`, `exp`, `jti = <sid>:<iat>`. Signed by `NimbusJwtEncoder` using the RSA private key already declared in `application.yaml` (`app.auth.jwt.private-key-pem-base64`, `active-kid`). Public keys serve JWKS-style verification.
- **Refresh token:** Opaque 32-byte URL-safe random. Only its SHA-256 hash reaches Redis and Postgres. Absolute 7-day TTL. Rotated on every `/refresh` via an atomic Lua swap (`scripts/refresh-token-rotate.lua`); the old hash lives 60s under `auth:refresh:used:<hash>` to distinguish reuse from unknown-token. Reuse triggers `RevokeAllSessionsUseCase.revokeAll(userId, TOKEN_REUSE)` and returns `REFRESH_TOKEN_REUSED (40103, 401)`.
- **Resource server:** `JwtConfiguration` builds a `NimbusJwtDecoder` from the configured public keys and installs a delegating validator: default (`iss`/`aud`/`exp`/skew) + `SessionAllowListValidator` (rejects when `auth:session:<sid>` is missing or revoked) + `CredentialVersionValidator` (rejects when JWT `cv` != cached user `credentialVersion`; misses fall through to `UserRepositoryPort`). Both cache reads use short TTLs so a bump/revoke propagates within `PT5M` even if we forget to invalidate.
- **Web:** `AuthController` gains seven methods. Public: `/login`, `/refresh`. Bearer-authenticated: `/logout`, `/sessions/revoke-all`, `/me`, `/sessions`, `/sessions/{sid}`. Add the two public paths to `SecurityConfiguration.PUBLIC_URLS` and delete the currently unused `/api/refresh-token` entry so the security config only advertises live endpoints.

## Endpoints

| Method | Path | Auth | Body | Success |
|---|---|---|---|---|
| POST | `/auth/login` | public | `LoginRequest{username, password}` | 200 `Response<LoginResponse>` |
| POST | `/auth/refresh` | public | `RefreshTokenRequest{refreshToken}` | 200 `Response<LoginResponse>` |
| POST | `/auth/logout` | bearer | none | 204 |
| POST | `/auth/sessions/revoke-all` | bearer | none | 204 |
| GET  | `/auth/me` | bearer | none | 200 `Response<MeResponse>` |
| GET  | `/auth/sessions` | bearer | none | 200 `Response<List<SessionResponse>>` |
| DELETE | `/auth/sessions/{sid}` | bearer | none | 204 |

`LoginResponse` fields: `tokenType="Bearer"`, `accessToken`, `expiresIn` (seconds), `refreshToken`, `refreshExpiresIn`, `sessionId`. `SessionResponse` fields: `sessionId`, `userAgent`, `createdAt`, `lastUsedAt`, `expiresAt`, `current` (boolean — true when it matches the caller's `sid`). `MeResponse` fields: `userId`, `username`, `email`, `displayName`, `roles`, `status`.

## Sequence flows

### Login (`POST /auth/login`)

1. Rate-limit: `authThrottlePort.tryConsume("login:ip:" + ipHash, ipMaxPerHour, Duration.ofHours(1))` AND `authThrottlePort.tryConsume("login:user:" + normalizedUsername, usernameMaxPerWindow, usernameWindow)`. Either denied → `warn` + `AUTH_RATE_LIMITED (429)`.
2. Load user by `normalizedUsername`. Miss → `warn` + `INVALID_CREDENTIALS`.
3. `user.isLocked(now)` → `warn` + `ACCOUNT_LOCKED`.
4. Password mismatch → `user.registerFailedLogin(now, maxFailures, lockDuration)` → `userRepositoryPort.save(user)` → publish `LOGIN_FAILED` and, if this call transitioned the account into lock, `ACCOUNT_LOCKED_OUT` → `warn` + `INVALID_CREDENTIALS`.
5. `user.isPendingVerify()` (checked only *after* the password matches, per `auth-module-requirements.md` AUTH-UC-05 step 9 / AC-05.6) → `warn` + `EMAIL_VERIFICATION_REQUIRED`.
6. `!user.isActive()` (remaining non-`ACTIVE` states, e.g. `DISABLED`, or soft-deleted) → `warn` + `INVALID_CREDENTIALS`.
7. Success → `user.resetFailedLogin()`; `userRepositoryPort.save(user)`.
8. `rawRefresh = refreshTokenGeneratorPort.generate()` (32 bytes URL-safe); `refreshHash = HashUtils.sha256(rawRefresh)`.
9. `session = Session.issue(user.id, user.credentialVersion, refreshHash, ua, ipHash, now, refreshTtl)`.
10. `sessionRepositoryPort.save(session)` (Redis atomic writes: `HSET auth:session:<sid> …`, `SET auth:refresh:<hash> <sid> PX <ttl>`, `SADD auth:user:<uid>:sessions <sid>`; RabbitMQ publish `SessionPersistEvent`).
11. `jwtIssuerPort.issue(user.id, sid, cv, roles, now)` → access token.
12. Publish `LOGIN_SUCCEEDED` audit event (after tx commit).
13. Return `LoginResponse`.

### Refresh (`POST /auth/refresh`)

1. Rate-limit by IP only (`authThrottlePort.tryConsume("refresh:ip:" + ipHash, ipMaxPerHour, Duration.ofHours(1))`).
2. `incomingHash = HashUtils.sha256(rawRefresh)`.
3. Run Lua `refresh-token-rotate.lua` with `KEYS = [auth:refresh:<incomingHash>, auth:refresh:used:<incomingHash>, auth:refresh:<newHash>, auth:session:<sid>]` and `ARGV = [expectedSid, newHash, remainingTtlMs, 60000, nowIso]`. Return `1` = rotated, `-1` = old hash not present.
   - Because we don't know `expectedSid` upfront, split into two steps: first `GET auth:refresh:<incomingHash>` to resolve `sid`; if absent → `GET auth:refresh:used:<incomingHash>` to distinguish reuse from unknown, then either `REFRESH_TOKEN_REUSED` (with revoke-all cascade) or `REFRESH_TOKEN_INVALID`. Only when the first `GET` returned a sid do we call the Lua swap.
4. Load session by `sid`; `!session.isActive(now)` or `session.expiresAt < now` → `REFRESH_TOKEN_INVALID`.
5. `cachedCv = credentialVersionCachePort.get(userId).orElseGet(() -> loadFromDb(userId))`. `session.credentialVersion != cachedCv` → revoke this session (`session.revoke(PASSWORD_CHANGED, now)`) and return `REFRESH_TOKEN_INVALID`.
6. `session.rotateRefresh(newHash, now)`; publish `SessionUpdateEvent`.
7. Issue new access token with **fresh `cv` from cache** (so a just-completed password change is visible immediately).
8. Publish `TOKEN_REFRESHED` audit; return `LoginResponse` (new access + new refresh, same `sessionId`).

### Reuse-detection cascade

When step 3's second `GET` reveals reuse: load `sid` from the `used:` entry → load session → `revokeAllForUser(session.userId, TOKEN_REUSE, now)` → increment user's `credentialVersion` (also invalidates any long-lived access tokens signed against the old `cv`) → audit `TOKEN_REUSE_DETECTED` → return `REFRESH_TOKEN_REUSED`. Do **not** issue new tokens. Do **not** leak the caller's identity in the response.

### Logout, Revoke-all, Revoke-one

- `Logout` → `sessionRepositoryPort.revoke(sidFromJwt, LOGOUT, now)`; audit `LOGOUT`.
- `Revoke-all` → `user.bumpCredentialVersion()` + `userRepositoryPort.save(user)` + `credentialVersionCachePort.invalidate(userId)` + `sessionRepositoryPort.revokeAllForUser(userId, USER_TRIGGERED, now)`; audit `ALL_SESSIONS_REVOKED`. The caller's current session is included; the response is 204 and the client discards its tokens.
- `Revoke-one` → load session by `sid` path variable; if session missing or `session.userId != caller.userId` → `SESSION_NOT_FOUND (404)` (enumeration-safe); else `session.revoke(USER_TRIGGERED, now)`; audit `SESSION_REVOKED`.

### Me / List sessions

- `Me` → resolve `sub` from JWT → `userRepositoryPort.findById` → project into `MeResponse` (no `passwordHash`, no `failedLoginCount`, no PII beyond what the caller already knows).
- `List sessions` → `sessionRepositoryPort.listActiveByUser(callerUserId)` → project each; mark `current=true` where `sid == callerSid`. Sorted by `lastUsedAt DESC`.

## Domain rules

- `User.registerFailedLogin(now, max, lockDuration)`: if `lockedUntil != null && now.isAfter(lockedUntil)`, reset counter to 0 first (new cycle). Increment; if `failedLoginCount >= max`, `lockedUntil = now + lockDuration`.
- `User.resetFailedLogin`: `failedLoginCount = 0; lockedUntil = null`.
- `User.bumpCredentialVersion`: `credentialVersion++; passwordChangedAt = now` (parameter). No other side effects.
- `User.isLocked(now)`: `lockedUntil != null && now.isBefore(lockedUntil)`. The existing no-arg method is removed; every call site uses the injected `Clock`.
- `Session.issue`: validates `refreshTokenHash` matches `[0-9a-f]{64}` (like `EmailVerificationToken`); `ipAddressHash` may be null but must not be blank.
- `Session.revoke`: idempotent — a second call with a different reason keeps the first reason (records the earliest revocation).
- `Session.isActive`: `revokedAt == null && deletedAt == null && now.isBefore(expiresAt)`.

## Persistence — Flyway `V202607190000__create_auth_sessions.sql`

```sql
CREATE TABLE auth_sessions (
    id                    UUID        PRIMARY KEY,
    user_id               UUID        NOT NULL REFERENCES auth_users(id),
    credential_version    INTEGER     NOT NULL,
    refresh_token_hash    CHAR(64)    NOT NULL,
    user_agent            VARCHAR(255),
    ip_address_hash       CHAR(64),
    created_at            TIMESTAMPTZ NOT NULL,
    last_used_at          TIMESTAMPTZ NOT NULL,
    expires_at            TIMESTAMPTZ NOT NULL,
    revoked_at            TIMESTAMPTZ,
    revoked_reason        VARCHAR(32),
    deleted_at            TIMESTAMPTZ,
    created_by            VARCHAR(64),
    last_modified_by      VARCHAR(64),
    last_modified_at      TIMESTAMPTZ,
    version               BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT auth_sessions_refresh_hash_ck CHECK (refresh_token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT auth_sessions_ip_hash_ck      CHECK (ip_address_hash IS NULL OR ip_address_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX auth_sessions_user_active_idx
    ON auth_sessions (user_id, last_used_at DESC)
    WHERE deleted_at IS NULL AND revoked_at IS NULL;

CREATE INDEX auth_sessions_refresh_hash_idx
    ON auth_sessions (refresh_token_hash)
    WHERE deleted_at IS NULL AND revoked_at IS NULL;

CREATE INDEX auth_sessions_expires_at_idx
    ON auth_sessions (expires_at)
    WHERE deleted_at IS NULL;
```

## Redis layout

| Key | Type | TTL | Notes |
|---|---|---|---|
| `auth:session:<sid>` | HASH | ~ session expiresAt | Fields: `userId`, `credentialVersion`, `refreshTokenHash`, `userAgent`, `ipAddressHash`, `createdAt`, `lastUsedAt`, `expiresAt`, `revokedAt` (optional), `revokedReason` (optional) |
| `auth:refresh:<sha256(rt)>` | STRING | remaining session TTL | Value = `sid` |
| `auth:refresh:used:<sha256(rt)>` | STRING | 60s | Reuse-detection grace window |
| `auth:user:<uid>:sessions` | SET | none (managed) | `{sid,...}` — cleaned on revoke/expiry |
| `auth:user:cv:<uid>` | STRING | `PT5M` | `credentialVersion` cache |
| `auth:throttle:login:ip:<ipHash>` | ZSET | 1h | Sliding-window Lua |
| `auth:throttle:login:user:<username>` | ZSET | 15m | Sliding-window Lua |
| `auth:throttle:refresh:ip:<ipHash>` | ZSET | 1h | Sliding-window Lua |

Prefix `auth:` is configurable via `AuthProperties.redis()`.

## JWT & validator chain

Access token structure (RS256, `kid` = `app.auth.jwt.active-kid`):

```json
{
  "iss": "file-processing",
  "aud": ["file-processing-api"],
  "sub": "<userId UUID>",
  "sid": "<sessionId UUID>",
  "cv":  1,
  "roles": ["OPERATOR"],
  "iat": 1750000000,
  "exp": 1750000900,
  "jti": "<sid>:<iat>"
}
```

`JwtConfiguration` beans:

- `JwtEncoder` — Nimbus, private RSA key + `kid`.
- `JwtDecoder` — Nimbus decoder over `AuthProperties.jwt().publicKeys()` (map by `kid`).
- `OAuth2TokenValidator<Jwt> defaultValidators` = `JwtValidators.createDefaultWithIssuer(issuer)` + custom `JwtAudienceValidator(audience)`.
- `OAuth2TokenValidator<Jwt> sessionAllowListValidator` — reads `sid`, calls `sessionRepositoryPort.findActiveById`; missing/revoked/expired → `invalid_token`.
- `OAuth2TokenValidator<Jwt> credentialVersionValidator` — reads `sub` + `cv`, compares to `credentialVersionCachePort.get(userId).orElseGet(() -> userRepositoryPort.findById(id).map(User::getCredentialVersion).orElseThrow(...))`; mismatch → `invalid_token`.

`JwtAuthenticationConverter` maps `roles` to `SimpleGrantedAuthority("ROLE_" + code)` so `@PreAuthorize("hasRole(...)")` works.

`SecurityConfiguration.oauth2ResourceServer(...)` receives this decoder bean.

## Rate limit & lockout config

Extend `AuthProperties`:

```java
public record Login(
    int ipMaxPerHour,          // 20
    int usernameMaxPerWindow,  // reuse existing max-failures = 5
    Duration usernameWindow,   // reuse existing failure-window = PT15M
    int maxFailures,           // existing = 5
    Duration failureWindow,    // existing = PT15M
    Duration lockDuration      // existing = PT15M
) {}

public record Refresh(Duration tokenTtl) {}  // PT168H

public record Session(Duration credentialVersionCacheTtl) {}  // PT5M
```

`application.yaml` gains:

```yaml
app:
  auth:
    login:
      ip-max-per-hour: ${AUTH_LOGIN_IP_MAX_PER_HOUR:20}
      username-max-per-window: ${AUTH_LOGIN_USERNAME_MAX_PER_WINDOW:5}
      username-window: ${AUTH_LOGIN_USERNAME_WINDOW:PT15M}
      max-failures: 5
      failure-window: PT15M
      lock-duration: PT15M
    refresh:
      token-ttl: ${AUTH_REFRESH_TOKEN_TTL:PT168H}
    session:
      credential-version-cache-ttl: ${AUTH_SESSION_CV_CACHE_TTL:PT5M}
    redis:
      session:
        key-prefix: ${AUTH_SESSION_REDIS_PREFIX:auth:session:}
      refresh:
        key-prefix: ${AUTH_REFRESH_REDIS_PREFIX:auth:refresh:}
        used-key-prefix: ${AUTH_REFRESH_USED_REDIS_PREFIX:auth:refresh:used:}
        reuse-detection-window: ${AUTH_REFRESH_REUSE_WINDOW:PT60S}
      credential-version:
        key-prefix: ${AUTH_CV_REDIS_PREFIX:auth:user:cv:}
      user-sessions:
        key-prefix: ${AUTH_USER_SESSIONS_REDIS_PREFIX:auth:user:sessions:}
    amqp:
      routing-key:
        session-persist: ${AUTH_AMQP_ROUTING_SESSION_PERSIST:auth.session.persist}
        session-update:  ${AUTH_AMQP_ROUTING_SESSION_UPDATE:auth.session.update}
        session-revoke:  ${AUTH_AMQP_ROUTING_SESSION_REVOKE:auth.session.revoke}
      queue:
        session-events:  ${AUTH_AMQP_QUEUE_SESSION_EVENTS:auth.session-events.queue}
```

## Error codes (added to `AuthErrorCode`)

| Constant | Code | HTTP | Meaning |
|---|---|---|---|
| `INVALID_CREDENTIALS` | 40101 | 401 | Wrong username/password, or account `DISABLED`/soft-deleted (enumeration-safe — checked only after the password matches) |
| `ACCOUNT_LOCKED` | 40301 | 403 | Lockout in effect; remaining time is not disclosed |
| `EMAIL_VERIFICATION_REQUIRED` | 40302 | 403 | Password matched but `user.status == PENDING_VERIFY`; matches `auth-module-requirements.md` AUTH-UC-05 step 9 / AC-05.6 (that doc's `40301` code number predates `ACCOUNT_LOCKED` claiming it and was never binding — the name is what's authoritative) |
| `REFRESH_TOKEN_INVALID` | 40102 | 401 | Refresh token unknown, expired, or session revoked / `cv` mismatched |
| `REFRESH_TOKEN_REUSED` | 40103 | 401 | Rotated refresh token was replayed |
| `SESSION_NOT_FOUND` | 40402 | 404 | Session id does not exist or is not owned by the caller |

All six names must be present in **both** `messages.properties` and `messages_vi.properties` (RULE §6.5). `AUTH_RATE_LIMITED` is reused for `/login` and `/refresh`.

## Audit event catalog (added to `OperationType`)

`LOGIN_SUCCEEDED`, `LOGIN_FAILED`, `ACCOUNT_LOCKED_OUT`, `TOKEN_REFRESHED`, `TOKEN_REUSE_DETECTED`, `LOGOUT`, `SESSION_REVOKED`, `ALL_SESSIONS_REVOKED`.

Every audit `AuditLog` carries: `id`, `domain=AUTH`, `objectId=userId` (or `sessionId` for session-scoped events), `operation`, `changedBy=userId` (`null` for pre-auth failures), `changedAt`, `ipAddress=ipHash`, `userAgent`. Publish after transaction commit via `AuditLogEventPublisherPort` (same pattern as register).

## Ports & file tree (new / renamed)

**Application ports:**
- `application/port/in/LoginUseCase.java`, `RefreshTokenUseCase.java`, `LogoutUseCase.java`, `RevokeAllSessionsUseCase.java`, `RevokeSessionUseCase.java`, `ListSessionsUseCase.java`, `GetCurrentUserUseCase.java`
- `application/port/out/SessionRepositoryPort.java`, `RefreshTokenGeneratorPort.java`, `JwtIssuerPort.java`, `CredentialVersionCachePort.java`, `SessionEventPublisherPort.java`
- **Rename** `application/port/out/RegisterThrottlePort.java` → `AuthThrottlePort.java` with signature `boolean tryConsume(String key, int max, Duration window)`. Update `RegisterService`.

**Application:**
- `application/command/LoginCommand.java`, `RefreshTokenCommand.java`, `LogoutCommand.java`, `RevokeSessionCommand.java`, `RevokeAllSessionsCommand.java`
- `application/query/ListSessionsQuery.java`, `GetCurrentUserQuery.java`
- `application/result/LoginResult.java`, `SessionResult.java`, `MeResult.java`
- `application/service/LoginService.java`, `RefreshTokenService.java`, `LogoutService.java`, `RevokeAllSessionsService.java`, `RevokeSessionService.java`, `ListSessionsService.java`, `GetCurrentUserService.java`

**Domain:**
- `domain/model/Session.java`, `domain/model/RevocationReason.java`
- **Edit** `domain/model/User.java` (add methods listed above)
- **Edit** `domain/model/OperationType.java` (add enum values)
- **Edit** `domain/exception/AuthErrorCode.java` (add codes)

**Adapter in — web:**
- **Edit** `adapter/in/web/AuthController.java`, `adapter/in/web/mapper/AuthWebMapper.java`
- `adapter/in/web/dto/request/LoginRequest.java`, `RefreshTokenRequest.java`
- `adapter/in/web/dto/response/LoginResponse.java`, `SessionResponse.java`, `MeResponse.java`

**Adapter in — amqp:**
- `adapter/in/amqp/SessionEventListener.java`

**Adapter out — cache:**
- `adapter/out/cache/RedisSessionRepositoryAdapter.java`, `RedisCredentialVersionCacheAdapter.java`
- **Rename** `adapter/out/cache/RedisRegisterThrottleAdapter.java` → `RedisAuthThrottleAdapter.java` (accept `window` argument, drop config-driven window). Adjust bean name.

**Adapter out — persistence:**
- `adapter/out/persistence/SessionPersistenceAdapter.java`
- `adapter/out/persistence/entity/SessionEntity.java`, `entity/JpaSessionRepository.java`
- `adapter/out/persistence/mapper/SessionPersistenceMapper.java`

**Adapter out — amqp:**
- `adapter/out/amqp/RabbitSessionEventPublisherAdapter.java`

**Adapter out — security:**
- `adapter/out/security/NimbusJwtIssuerAdapter.java`, `SessionAllowListJwtValidator.java`, `CredentialVersionJwtValidator.java`
- `adapter/out/security/SecureRefreshTokenGeneratorAdapter.java`

**Configuration:**
- **Edit** `auth/configuration/AuthProperties.java` (extend records)
- **Edit** `auth/configuration/AuthAmqpConfiguration.java` (add session queue + bindings + DLQ)
- **Edit** `auth/configuration/AuthRedisConfiguration.java` (add `refreshTokenRotateScript`, `sessionRevokeAllScript`)
- `auth/configuration/JwtConfiguration.java` (new)
- **Edit** `configuration/security/SecurityConfiguration.java` (public URLs, JWT decoder wiring)

**Resources:**
- `src/main/resources/scripts/refresh-token-rotate.lua`
- `src/main/resources/scripts/session-revoke-all.lua` (batch DEL of session hash + refresh string + SREM index; ARGV = sid list)
- `src/main/resources/db/migration/V202607190000__create_auth_sessions.sql`
- Update `i18n/messages.properties` + `messages_vi.properties`

## Testing plan

**Unit tests (no Spring context):**
- `SessionDomainTest` — issue validation, rotate rejects revoked/expired, revoke idempotent, isActive edges.
- `UserDomainTest` extension — `registerFailedLogin` cycle (reset when past lock), `resetFailedLogin`, `bumpCredentialVersion`, `isLocked(now)`.
- `LoginServiceTest` — happy, unknown user, locked, wrong password (counter increments, lock-transition audit fires), pending-verify with correct password (returns `EMAIL_VERIFICATION_REQUIRED`), pending-verify with wrong password (still `INVALID_CREDENTIALS` — password checked first), disabled account, rate-limit denies both buckets.
- `RefreshTokenServiceTest` — happy, unknown refresh, reused refresh (cascade revoke + `REFRESH_TOKEN_REUSED`), expired session, `cv` mismatch, rate-limit.
- `LogoutServiceTest`, `RevokeAllSessionsServiceTest`, `RevokeSessionServiceTest` (including foreign-owner returns 404), `ListSessionsServiceTest`, `GetCurrentUserServiceTest`.

**Integration tests (`AuthControllerIT`, Testcontainers Postgres + Redis + RabbitMQ already wired via `AuthIntegrationTestBase`):**
- Login happy → 200 with two tokens; access token verifies against the resource server (spring-test call to `/auth/me` succeeds).
- Login wrong password 5 times → 6th call returns `ACCOUNT_LOCKED` even with correct password; after 15 min (advance a `Clock` bean) login works again.
- Refresh happy → new tokens, old refresh no longer accepted.
- Refresh reuse (submit the old token after rotation) → 401 `REFRESH_TOKEN_REUSED`; a subsequent `/me` with the pre-reuse access token also returns 401.
- Logout → subsequent `/me` returns 401 `invalid_token`.
- Revoke-all → every device's `/me` returns 401.
- Revoke one session while another session for the same user keeps working.
- `/sessions` includes `current=true` for the caller.
- Public-URL check: `/auth/login` and `/auth/refresh` succeed without a bearer; `/api/refresh-token` (legacy) is gone.

Coverage floor: unit ≥ 80 % of new services; integration exercises every error branch in `AuthErrorCode` we added.

## Rollout notes

- Dev bootstrap: `application-dev.yml` currently loads a dev public key. Extend `AUTH_JWT_DEV_PUBLIC_KEY_LOCATION` handling with `AUTH_JWT_ACTIVE_KID` + a matching PEM pair; document in `README` how to generate a keypair for dev (`openssl genrsa` + `openssl rsa -pubout`). Do not commit real keys.
- `SecurityConfiguration.PUBLIC_URLS` — add `/api/v1/auth/login`, `/api/v1/auth/refresh`; remove the unused `/api/refresh-token`.
- Register flow keeps working — the port rename is source-compatible after `RegisterService` is updated in the same commit.
- No API breakage for the (currently unused) resource server — the JWT decoder replacement inherits `spring.security.oauth2.resourceserver.jwt.public-key-location` for local runs but the encoder side is entirely new.

## Definition of done

- All new endpoints ship with matching unit + integration tests, all green under `mvn spotless:apply && mvn verify`.
- No plaintext refresh token or password appears in Redis, Postgres, logs, or audit metadata.
- Reuse detection covered by integration test; a reused refresh always ends in `REFRESH_TOKEN_REUSED` + full revoke.
- A newly bumped `credentialVersion` invalidates access tokens across the fleet within 5 minutes (cache TTL) without any restart.
- `AuthThrottlePort` rename is reflected in `RegisterService` and both existing register tests, with no behavior change.
