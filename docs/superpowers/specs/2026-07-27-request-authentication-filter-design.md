# Request Authentication Filter Design

**Date:** 2026-07-27

**Scope:** Refactor `CustomAuthenticationFilter` into a hexagonal inbound security adapter. It reloads the authenticated user's current permissions for every JWT request and publishes a common-library `UserAuthentication` into the security context.

## Decisions

- The filter remains an inbound security adapter and contains no user lookup or permission-resolution logic.
- Add `ResolveRequestAuthenticationUseCase` in `auth/application/port/in`.
- Its implementation loads the user through `UserRepositoryPort`, rejects a missing or non-`ACTIVE` user with `AuthDomainException(AuthErrorCode.INVALID_CREDENTIALS)`, and delegates permission resolution, including role inheritance and inactive-role exclusion, to the existing `AuthorityService`.
- The use case returns an immutable result containing only `userId`, `username`, and permission strings. It does not expose a domain `User` to the filter.
- The filter runs only after JWT authentication has populated a `UserAuthentication`. It uses that verified authentication's user ID and JWT, calls the use case, converts permissions to `SimpleGrantedAuthority`, and replaces the context authentication with a new common-library `UserAuthentication`.
- `JwtConfiguration` remains responsible for JWT signature, issuer, audience, type, session, and credential-version validation. The filter does not invalidate raw tokens or duplicate those checks.
- Do not add `TokenCacheService`, `UserAuthorityCustom`, or a custom Spring authentication token. Those belong to the legacy example and are unnecessary because this codebase already uses `UserAuthentication` and `CredentialVersionJwtValidator`.

## Flow

1. `BearerTokenAuthenticationFilter` validates the JWT and `JwtAuthenticationConverter` creates `UserAuthentication`.
2. `CustomAuthenticationFilter` skips requests without `UserAuthentication`.
3. The filter calls `ResolveRequestAuthenticationUseCase` with the authenticated user ID.
4. The application service verifies the user is active and reloads current permissions through `AuthorityService`.
5. The filter creates a new `UserAuthentication` using the verified JWT and refreshed authorities, then stores it in `SecurityContextHolder`.
6. The remaining filter chain and method-security checks see the refreshed permissions.

## Error Handling

- A missing or non-active user returns `401 INVALID_CREDENTIALS`, without disclosing whether the account exists or is disabled.
- Unexpected persistence failures propagate to the existing exception handling path; the filter does not log JWTs or user data.

## Tests

- Unit-test the application service for active users, missing users, and disabled users.
- Unit-test the filter for refreshed permissions, skipped non-JWT requests, and rejection propagation.
- Extend security integration coverage to prove a role-permission change is reflected on the next authenticated request.

## Out of Scope

- Changing JWT claims, session validation, credential-version semantics, cache topology, role-management behavior, or endpoint authorization rules.
