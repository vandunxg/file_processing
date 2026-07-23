# Auth V1 Completion Design

## Goal

Complete the approved V1 authentication and authorization module without
adding MFA, social login, key-management APIs, or other out-of-scope identity
features. This design completes the missing bootstrap, password, JWT/JWKS,
Admin/RBAC, durable refresh-session, and operational flows around the existing
register, email-verification, login, and session work.

## Approved Decisions

- Production fails during startup when the active signing key or its matching
  public key is missing or malformed. An ephemeral RSA key is permitted only by
  an explicit development-only property.
- If PostgreSQL contains no users, valid bootstrap-Admin configuration is
  required. A populated database skips bootstrap even when those values are
  absent.
- Refresh tokens are carried only in the `fps_refresh` HttpOnly cookie. The
  `fps_csrf` cookie plus `X-CSRF-Token` header protect refresh rotation.
- JWT rotation is performed by a system operator through environment
  configuration and deployment. There is no runtime key-management API.
- Self-service paths move to `/api/v1/me` and `/api/v1/me/sessions`; the old
  `/api/v1/auth/me` and `/api/v1/auth/sessions/**` paths are removed rather
  than retained as aliases.
- `POST /api/v1/auth/forgot-password` keeps rate limits but returns
  `404 USER_NOT_FOUND` when the identifier does not resolve to a user. A
  `DISABLED` user still receives `204 No Content`.

The final item intentionally overrides the enumeration-safe behavior in
`docs/specs/auth-module-requirements.md` AUTH-UC-12. The API specification,
error catalog, and tests must document this approved business-rule change.

## Scope

In scope:

- Bootstrap the first Admin safely across concurrent application instances.
- Complete first-login, self-service change, forgot-password, and reset
  password flows.
- Replace the current Redis-first refresh-session persistence model with the
  approved durable session and token model.
- Publish JWKS, validate access tokens through a configured key ring, and
  support deployment-driven signing-key rotation.
- Complete Admin user lifecycle, dynamic role/permission management, audit
  read, and ownership-aware authorization.
- Align refresh cookies, CSRF, CORS, OpenAPI, metrics, tests, and operational
  documentation with the approved V1 requirements.

Out of scope:

- MFA, passwordless login, social or enterprise identity providers, OAuth
  authorization-server behavior, API keys, service accounts, and an Admin key
  rotation API.

## Security Architecture

### Key Ring And JWKS

`AuthProperties` owns the active signing key, trusted public-key ring, issuer,
audience, cookie settings, bootstrap values, token TTLs, and rate limits.

`JwtKeyRing` parses the configured base64-encoded PEM material at startup:

- The active private key must match a public key with `activeKid`.
- Every public key has a unique nonblank `kid`.
- Production rejects absent, malformed, non-RSA, or mismatched keys before it
  accepts requests.
- Development can generate an ephemeral key only when an explicit
  development-only property enables it. It is never the production fallback.

Access tokens use RS256 and contain `iss`, `aud`, `sub`, `sid`, `jti`, `cv`,
`typ=access`, `roles`, `permissions`, and standard issue/not-before/expiry
claims. The decoder selects a configured public key by `kid`, accepts RS256
only, validates issuer/audience/type/clock claims, and then validates the
credential version and active session.

`GET /api/v1/certificate/.well-known/jwks.json` is public and returns only
public JWKs. It includes the active signing key and old verification keys until
all access tokens issued by each old key have expired. A rotation deploy first
adds the new public key, then changes `activeKid`; a later deploy removes an old
key only after that retention period. The endpoint never exposes private PEM,
storage locations, or configuration diagnostics.

### Security Chains And Authorities

The normal resource-server chain accepts access tokens only and installs the
configured JWT decoder plus a converter that creates authorities from both
`roles` and `permissions`. `permissions` use the existing
`resource:action` format so `RegexPermissionEvaluator` can honor
`all:manage`.

Password-change tokens have a separate decoder/validator restricted to
`typ=password_change`, its five-minute TTL, issuer, audience, subject, and
credential version. They authenticate only
`POST /api/v1/auth/complete-password-change`; they cannot call business APIs.
Conversely, an access token cannot complete the forced-change flow.

Protected controllers use permission expressions. Repository queries and
application services apply owner predicates for self-scoped permissions; a
foreign resource is indistinguishable from a missing one and returns `404`.

### Refresh Cookies, CSRF, And CORS

Successful normal login and refresh set:

```text
fps_refresh=<opaque>; HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth
fps_csrf=<random>; Secure; SameSite=Strict; Path=/api/v1/auth
```

The refresh token never appears in a response DTO, request body, log, metric,
or audit record. `POST /api/v1/auth/refresh` reads the cookie and compares the
cookie CSRF value with `X-CSRF-Token` using a constant-time comparison. Invalid
CSRF returns `403 CSRF_TOKEN_INVALID`; absent or invalid refresh material
returns the appropriate sanitized refresh error. CORS origins, headers, and
credentials are configured as an environment-specific allowlist, including
`X-CSRF-Token`.

## Account And Password Flows

### Bootstrap Admin

`BootstrapAdminListener` invokes `BootstrapAdminUseCase` on
`ApplicationReadyEvent`. The use case takes a PostgreSQL transaction advisory
lock, rechecks whether any non-deleted user exists, and then either skips or
creates exactly one Admin.

When it creates an Admin, it validates the configured username, email, display
name, and password; hashes the password; assigns the seeded `ADMIN` role; and
persists an `ACTIVE`, email-verified user with `mustChangePassword=true` and
credential version one. It writes `ADMIN_BOOTSTRAPPED` audit data without a
password or secret. The lock makes two simultaneous application starts safe.

### Forced And Self-Service Password Changes

When a valid `ACTIVE` user has `mustChangePassword=true`, login verifies the
password but creates neither a refresh session nor an access token. It instead
returns a short-lived password-change JWT. Completing this flow requires the
current password, a matching confirmation, and a policy-compliant new password;
it clears `mustChangePassword` and requires a fresh login.

`POST /api/v1/auth/change-password` is the corresponding access-token flow.
It verifies the current password before changing it. Both flows reject a new
password that equals the username, email, or current password. The shared
password policy remains 8-128 Unicode characters and rejects all-whitespace
passwords; no artificial composition rule is added.

Every successful password change bumps `credentialVersion`, resets the password
timestamp, revokes all refresh sessions, and invalidates the credential cache
after commit. Password material is BCrypt cost 12 through the configured
password-hasher port, with rehash-on-login when its configured cost changes.

### Forgot And Reset Password

`POST /api/v1/auth/forgot-password` rate-limits by IP and normalized
identifier before lookup. A missing user returns `404 USER_NOT_FOUND`; a
`DISABLED` user returns `204`. For an eligible user, the transaction consumes
older reset tokens, creates a 256-bit opaque token with only its SHA-256 hash
persisted, and records `PASSWORD_RESET_REQUESTED`. Email delivery happens after
commit.

`POST /api/v1/auth/reset-password` hashes and locks the supplied token. It
rejects unknown, expired, or consumed tokens with
`410 PASSWORD_RESET_TOKEN_INVALID`; checks the new password and confirmation;
consumes the token; changes the password; clears login failures and lockout;
bumps credential version; revokes all sessions; and publishes
`PASSWORD_RESET_COMPLETED` audit data. A reset by a `PENDING_VERIFY` user makes
the account `ACTIVE` and marks its email verified because the reset link proves
control of that mailbox.

## Durable Refresh Sessions

PostgreSQL is the correctness boundary for refresh behavior. The current
Redis-first `auth_sessions` plus asynchronous Rabbit archive cannot durably
identify every consumed refresh token after its short Redis reuse window.

New migrations add the approved structures:

- `auth_refresh_sessions`: session/family identity, user, credential-version
  snapshot, device and IP metadata, absolute expiry, revocation state, and
  optimistic version.
- `auth_refresh_tokens`: one unique SHA-256 token hash per issued refresh
  token, parent-token lineage, issue/expiry timestamps, and consumed/revoked
  state.
- `auth_password_reset_tokens`: token hash, user, issue/expiry/use timestamps,
  and an IP hash.

The old `auth_sessions` table is not modified or used after cut-over. Its
sessions are intentionally invalidated: hashes alone cannot safely reconstruct
token-family history. A later retention migration may remove obsolete rows
after their absolute expiry.

Login inserts the session and first token in its PostgreSQL transaction.
Refresh locks the token and session rows, consumes the old token, inserts one
child token with the same absolute expiry, and commits before new cookies are
returned. A replayed consumed/revoked token locks and revokes the whole family,
bumps the user's credential version, and emits `TOKEN_REUSE_DETECTED`. This
also serializes concurrent refresh requests correctly: exactly one succeeds.

Redis remains a bounded cache for credential versions, rate limiting, and
optional active-session reads. Cache writes and eviction occur after commit;
PostgreSQL is the fallback and source of truth. Rabbit remains appropriate for
after-commit email and audit delivery, not for committing security state.

The `User` domain and persistence mapper also begin tracking `lastLoginAt`, a
column that already exists but is currently ignored by the domain mapper.

## Self-Service And Administration APIs

Credential endpoints remain under `/api/v1/auth`:

- `POST /register`, `/verify-email`, `/resend-verification`, `/login`,
  `/refresh`, `/logout`, `/complete-password-change`, `/change-password`,
  `/forgot-password`, and `/reset-password`.

Self-service endpoints are canonical under `/api/v1/me`:

- `GET /api/v1/me`.
- `GET /api/v1/me/sessions`.
- `DELETE /api/v1/me/sessions/{sessionId}`.
- `POST /api/v1/me/sessions/revoke-all`.

The previous `/api/v1/auth/me` and `/api/v1/auth/sessions/**` endpoints are
removed, not aliased.

Admin endpoints under `/api/v1/users`, `/api/v1/roles`,
`/api/v1/roles/permissions`, `/api/v1/roles/resources`, and
`/api/v1/admin/audit-logs` enforce permissions rather than a hardcoded Admin
role. They provide the approved user creation, search/detail, profile/role
update, disable/enable/unlock, temporary-password reset, role CRUD,
inheritance, permission catalog, role audit, and system audit capabilities.

Admin-created users can be auto-verified or receive normal verification email,
but always start with `mustChangePassword=true`. An Admin reset creates the
same forced-change state. Neither response ever exposes a temporary password.

Role mutations enforce the existing constants: `ADMIN` retains `all:manage`,
constant roles cannot be deleted or renamed, inactive roles grant no
permissions, and inheritance cannot form a cycle. Changes to a role or its
permissions identify affected users, revoke their sessions, bump their
credential versions, and evict their authority/credential caches after commit.

Any mutation that could disable, delete, or remove the Admin role from the last
active Admin takes a PostgreSQL advisory lock and checks the invariant while
holding it. This prevents concurrent requests from leaving the system without
an active Admin.

## Auditing, Metrics, And Cleanup

All audit metadata is sanitized and includes an actor when one exists. Required
events include bootstrap, password requests/completions/changes, login success
and failure, lockout, refresh and reuse, session actions, user status and role
actions, role permission/inheritance changes, and JWKS rotation operations.

Micrometer counters cover authentication outcomes, login/forgot/refresh rate
limits, token reuse, password reset outcomes, and JWKS validation failures.
They use fixed low-cardinality labels only. Passwords, raw opaque tokens, JWTs,
key material, full email addresses, and unmasked phone values are excluded from
logs, metrics, API errors, and audit metadata.

Scheduled cleanup removes expired reset and verification tokens and expired
refresh-token/session records. It is idempotent, bounded, observable, and does
not remove a still-active refresh session.

## Test And Delivery Plan

Unit tests cover domain transitions, password policy, forced-change and reset
services, key-ring validation, JWKS projection, permission inheritance, and
last-active-Admin guards.

Testcontainers integration tests cover:

- Concurrent bootstrap creates exactly one Admin.
- Normal login uses secure cookies and does not serialize refresh material.
- Missing-user forgot-password returns `404 USER_NOT_FOUND`; rate-limit denial
  still returns `429`.
- Reset expiry, single-use, pending-verification activation, lockout clearing,
  and full session invalidation.
- Forced change token type and expiry restrictions.
- Refresh CSRF rejection, concurrent rotation, and replay revocation across a
  PostgreSQL restart.
- JWKS contains no private fields; tokens signed by retained old keys verify
  until their planned removal, while unknown `kid`, wrong algorithm, type, or
  audience fail.
- Admin and operator authorization, ownership `404`, role inheritance, role
  mutation invalidation, and last-active-Admin races.
- Migration correctness, sanitization, audit/metric emission, and the new
  OpenAPI contracts.

Delivery updates OpenAPI, API error messages, configuration samples, email
templates, JWT/bootstrap/key-rotation runbooks, and the source authentication
requirements to record the two approved behavior changes. The deployment runbook requires signing-key configuration before production rollout and states
that pre-cut-over refresh sessions require a new login.
