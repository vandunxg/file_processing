# Authenticated Principal Auditing Design

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

## Scope

Authenticated JPA writes must persist the authenticated username in
`created_by` and `last_modified_by`. This implements the intent of
AUTH-UC-16 step 7 using the username audit convention requested for this
service. Existing public and background flows continue to use their existing
non-user auditor value (`anonymous`).

## Decision

Use an application `AuthenticatedUser` principal rather than a request
attribute or an additional JWT claim. It implements Spring Security
`UserDetails` and carries the immutable request identity required by the web
layer:

- `userId`
- `username`
- `sessionId`

The existing `UserAuthentication` remains the `Authentication` type so the
shared `SecurityUtils`, authorization filters, and permission evaluator remain
compatible. Its principal changes from the raw `Jwt` to `AuthenticatedUser`.
Its credentials and raw-token fields continue to carry the JWT and token value.
Because `UsernamePasswordAuthenticationToken.getName()` delegates to a
`UserDetails` principal, the existing `SpringSecurityAuditorAware` resolves the
username without a replacement auditor bean.

## Request Flow

1. The JWT converter creates the existing preliminary `UserAuthentication`
   with the user ID from `sub` and the JWT as principal.
2. `CustomAuthenticationFilter` validates the user ID and loads the current
   username and permissions through `ResolveRequestAuthenticationUseCase`.
3. The filter reads `sid` from the validated JWT, constructs
   `AuthenticatedUser`, and replaces the security context with a
   `UserAuthentication` using that principal.
4. Controllers read `AuthenticatedUser` with `@AuthenticationPrincipal` for
   user and session identity. They no longer parse the JWT subject or claims.
5. Spring Data JPA invokes the existing `SpringSecurityAuditorAware`; its
   current auditor is the username returned by `Authentication.getName()`.

The filter remains the only component that turns a token into the current
application identity. No controller accepts an audit username from an HTTP
request body.

## Compatibility And Boundaries

- No JWT contract change: `sub` remains the user ID and `sid` remains the
  session ID claim.
- No migration: all audited tables already have `created_by` and
  `last_modified_by` columns.
- No `AuditorAware` replacement: the shared auditor remains the single audit
  integration point.
- Public registration, bootstrap, workers, scheduled work, and asynchronous
  listeners do not have an authenticated principal and retain `anonymous`.
- `audit_logs` and `action_logs` are system event records. Their audit metadata
  intentionally remains `anonymous`; their business actor continues to be
  recorded in their dedicated event fields such as `changedBy` or `username`.
- Usernames are not mutable through the current API. If that changes, audit
  values remain the historical username snapshot at write time.

## Affected Web Endpoints

Migrate authenticated controllers that currently depend on
`@AuthenticationPrincipal Jwt`:

- `POST /api/v1/auth/logout`
- `GET`, `DELETE`, and `POST /api/v1/me/sessions/**`
- `GET /api/v1/me`
- Admin user create and management actions

Role management already obtains the user ID from `SecurityUtils` and requires
no controller identity change.

## Verification

- Unit-test `CustomAuthenticationFilter` to assert that the replacement
  principal exposes the resolved username, user ID, session ID, and current
  permissions.
- Update controller tests for the migrated principal contract, including
  logout and current-session operations.
- Add PostgreSQL integration coverage that creates a role and a user through
  authenticated requests, then asserts the persisted `created_by` and
  `last_modified_by` values equal the caller username.
- Add an authenticated update by a distinct user and assert only
  `last_modified_by` changes to that user's username.
