---
name: backend-security
description: Use when touching authentication, authorization, roles or permissions, resource ownership checks, JWT or refresh tokens, passwords, rate limiting, untrusted file or parser input, or SecurityFilterChain configuration.
metadata:
  version: "0.0.1"
  author: vandunxg
  scope: shared
  category: security
  stack: java-21, spring-boot-4
---

# Backend security

Every rule here is **MUST**.

- Deny by default; grant the minimum permission.
- Enforce authorization in the application/security boundary, never only by
  hiding UI actions.
- Validate ownership and tenant boundaries on every resource access. Default:
  another owner's resource returns `404`, not `403`, so existence is not
  leaked (the project's requirements may override).
- Never trust roles or permissions from request payloads.
- JWT role/permission mapping is explicit and tested.
- Refresh tokens rotate; reuse of a consumed token revokes the token family.
- Passwords use an adaptive hash through Spring Security's `PasswordEncoder`.
- Do not reveal whether a username or email exists where that enables
  account enumeration.
- File names, media types, archive entries, and parser input are untrusted.
- Rate-limit authentication and other abuse-sensitive endpoints.
- Security events produce an audit record or metric with masked identifiers.
- No secrets in source control or logs; no hard-coded credentials.
- No SQL built by string concatenation; escape `%`, `_`, `\` in `LIKE` input.
- CSRF may be disabled only when credentials travel in the `Authorization`
  header. Once a token rides in a cookie (e.g. refresh token), keep CSRF
  protection (`CookieCsrfTokenRepository`) or use `SameSite=Strict` plus an
  Origin check.
- CORS with `allowCredentials(true)` lists explicit origins from config, never `*`.
- Actuator: permit `/actuator/health/**` (probes) only; `show-details:
  when_authorized`; every other endpoint requires authentication or a separate
  management port.
- Use `SecurityFilterChain` beans (never `WebSecurityConfigurerAdapter`);
  `@EnableWebSecurity` always alongside `@Configuration`. Invalid tokens are a
  `401` from the entry point, not an `error` log.
  Reuse the JWT/resource-server setup from `vandunxg-common` (`common-web`)
  before writing filters.

Related: `logging` (sensitive data), `app-configuration` (secrets).
