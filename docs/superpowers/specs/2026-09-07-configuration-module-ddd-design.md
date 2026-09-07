# Configuration Module — Pragmatic Modular DDD Migration Design

**Date:** 2026-09-07

**Scope:** Migrate `com.vandunxg.file_processing.configuration` to the target
architecture in [`ARCHITECTURE.md`](../../../ARCHITECTURE.md) and
[`RULE.md`](../../../RULE.md) §4. The module keeps its role — *application-wide
technical configuration only* — but stops owning technical configuration and
route knowledge that belongs to the `auth` module.

**Not in scope:** `fileimport` (still on the legacy `adapter/in|out` layout, a
separate phase), business behavior, HTTP contracts, database schema, security
semantics.

---

## 1. Problem

`configuration/` currently violates module ownership in four ways.

| Finding | Evidence |
|---|---|
| App-wide config owns an auth-only capability | `EmailConfiguration` wires `MailService`/`MailSenderFactory`; the only consumer is `auth/infrastructure/email/MailServiceEmailSender` |
| Composition root knows a module's URL contract | `SecurityConfiguration.PUBLIC_URLS` hardcodes eight `/api/v1/auth/*` routes plus `/api/v1/certificate/.well-known/jwks.json`, which `auth/api/JwksController` serves |
| Composition root owns a module's authentication mechanism | `SecurityConfiguration.filterChain` builds the `JwtAuthenticationProvider` and calls `oauth2ResourceServer(...)` from beans that `auth/infrastructure/config/JwtConfiguration` produces |
| Dead bean | `SecurityConfiguration.passwordEncoder()` has no consumer: `BcryptPasswordHasher` constructs its own `DelegatingPasswordEncoder` from `AuthProperties.password().bcryptCost()`, and `common-web` never injects a `PasswordEncoder` |

Plus a packaging issue: `EmailConfiguration`, `OpenApiConfiguration`, and
`InstantEpochMillisSerializer` sit flat at the module root, mixing three
unrelated concerns; only `security/` is grouped.

`SecurityFilterChainContributor` already exists and already solves the general
version of finding 2 and 3 — the composition root must not import a module's
`infrastructure` package. This design extends that pattern instead of inventing
a new one.

## 2. Target structure

```text
configuration/                             # composition root
├── openapi/
│   └── OpenApiConfiguration.java
├── serialization/
│   └── InstantEpochMillisSerializer.java
└── security/
    ├── AuthenticatedUser.java             # shared security kernel
    ├── ModuleSecurityContributor.java     # extension point
    └── SecurityConfiguration.java         # chain skeleton + platform rules

auth/infrastructure/
├── config/
│   └── EmailConfiguration.java            # moved from configuration/
└── security/
    └── AuthSecurityContributor.java       # renamed, widened
```

After the migration `configuration` has zero imports from `auth` or
`fileimport`, and zero knowledge of any module's routes or authentication
mechanism.

## 3. Decisions

### 3.1 `AuthenticatedUser` stays in `configuration/security`

It is the principal contract the composition root establishes and every
module's `api` layer reads through `@AuthenticationPrincipal` — `auth.api`,
`auth.infrastructure.security`, and `fileimport.adapter.in.web` all consume it.
Treat it as a small shared security kernel alongside `ModuleSecurityContributor`,
the same way the root owns the extension point that modules implement.

Rejected: moving it to `auth/api` or `auth/application`. Both would make
`fileimport` depend on the `auth` module for a Spring Security framework type,
a dependency the allowed-direction table does not cover (it permits
`module A -> module B application capability`, i.e. a call, not a type).

### 3.2 One extension point, not two

`SecurityFilterChainContributor` is renamed to `ModuleSecurityContributor` and
gains a second member, because a module now declares two cohesive things about
itself. RULE.md §4.5: *group cohesive operations into one contract when that
keeps the API understandable.*

```java
public interface ModuleSecurityContributor {

  /** Request path patterns this module serves without authentication. */
  default List<String> publicPaths() {
    return List.of();
  }

  /** Installs this module's authentication mechanism and filters. */
  default void contribute(HttpSecurity http) throws Exception {}
}
```

Both members default so a module implements only what it needs. `@Order`
continues to govern filter placement; it has no effect on `publicPaths()`,
which the root unions.

### 3.3 `auth` owns its public paths and its authentication mechanism

`AuthSecurityFilterChainContributor` becomes `AuthSecurityContributor` and
declares all three things `auth` owns in the application security chain:

- `publicPaths()` — the nine routes listed in §5.1, verbatim from today's
  `PUBLIC_URLS`;
- `contribute()` — the `JwtAuthenticationProvider` construction and
  `http.oauth2ResourceServer(...)` call moved out of `SecurityConfiguration`,
  followed by the two existing `addFilterAfter` calls, unchanged.

### 3.4 `SecurityConfiguration` keeps only platform concerns

Retained: `@EnableWebSecurity`, `@EnableMethodSecurity(securedEnabled = true)`,
CSRF disabled, `SessionCreationPolicy.STATELESS`, `IGNORE_URLS`,
`PLATFORM_PUBLIC_URLS`, the actuator rules, `/api/**` authenticated,
`exceptionHandling` with `CustomAuthenticationEntryPoint`, the two `common-web`
filters (`NoHandlerFoundFilter`, `ForbiddenTokenFilter`), the contributor loop,
`webSecurityCustomizer()`, `expressionHandler()`, and
`springSecurityAuditorAware()`.

`CustomAuthenticationEntryPoint` stays at the root deliberately: it answers
*how the application responds to an unauthenticated request*, which is
independent of which module authenticates it.

Removed: `passwordEncoder()`. It is dead code, not misplaced code — moving it
into `auth` would relocate a bean nobody injects. Verified against
`BcryptPasswordHasher`, `common-web` sources, and the test sources.

### 3.5 Package grouping

`configuration/{openapi,serialization,security}`. `serialization/` makes
`InstantEpochMillisSerializer`'s nature explicit — it is a `@JacksonComponent`,
not configuration. Two of the three packages hold one file each; this is
grouping existing types by concern, not creating placeholder packages, so
RULE.md §4.1's prohibition on empty packages does not apply.

## 4. Behavior preservation

The migration must be behavior-neutral. Two mechanisms carry real risk.

### 4.1 `web.ignoring()` must receive the same path set

`PUBLIC_URLS` is used in two places today:

```text
filterChain()           -> authorizeHttpRequests(...).requestMatchers(PUBLIC_URLS).permitAll()
webSecurityCustomizer() -> web.ignoring().requestMatchers(PUBLIC_URLS).requestMatchers(IGNORE_URLS)
```

`web.ignoring()` bypasses the entire filter chain, so it dominates: today
`/api/v1/auth/login` never reaches `ForbiddenTokenFilter`,
`NoHandlerFoundFilter`, `CustomAuthenticationFilter`, or `ActionLoggingFilter`.

**Requirement:** module-contributed paths MUST feed both call sites, exactly as
the static array does. Feeding only `permitAll()` would start running four
filters on the auth endpoints — a behavior change disguised as a refactor.

Contributors are resolved lazily inside the `WebSecurityCustomizer` lambda, not
at bean-construction time. `WebSecurityConfiguration` applies customizers while
building the same `WebSecurity` that builds the `SecurityFilterChain`, so this
resolves the contributor beans no earlier than the chain already does.

### 4.2 Filter order must not shift

`HttpSecurity.addFilterAfter(filter, BearerTokenAuthenticationFilter.class)`
computes its position from the static `FilterOrderRegistry`, so the position
does not depend on whether `oauth2ResourceServer(...)` was configured before or
after the call. Moving that call into a contributor — which runs *after* the
root adds `NoHandlerFoundFilter` and `ForbiddenTokenFilter` — therefore
produces the same chain.

`addFilterAfter(actionLoggingFilter, CustomAuthenticationFilter.class)` still
works because `CustomAuthenticationFilter` is registered into the local order
map by the preceding `addFilterAfter` call in the same contributor.

`SecurityConfigurationIT` asserts real filter positions and is the guard here.

### 4.3 Matcher ordering inside `authorizeHttpRequests`

Today's `PUBLIC_URLS` array order is: platform paths (`/`, `/health`, `/ready`,
`/ws/**`, `/api/public/**`), then auth paths, then the JWKS path. Splitting it
into `PLATFORM_PUBLIC_URLS` followed by the contributed paths preserves that
order, and both still precede the actuator rules.

### 4.4 Empty contribution

When no contributor declares a public path, the root MUST skip the
`requestMatchers(...)` call rather than pass an empty array.

### 4.5 Fail-closed check

If no module contributes an authentication mechanism, the chain has no
resource server, `/api/**` remains `.authenticated()`, and every non-public
request is rejected. The failure mode is closed, not open.

## 5. Reference data

### 5.1 Paths moving to `AuthSecurityContributor.publicPaths()`

```text
/api/v1/auth/register
/api/v1/auth/verify-email
/api/v1/auth/resend-verification
/api/v1/auth/forgot-password
/api/v1/auth/reset-password
/api/v1/auth/complete-password-change
/api/v1/auth/login
/api/v1/auth/refresh
/api/v1/certificate/.well-known/jwks.json
```

### 5.2 Paths staying in `SecurityConfiguration.PLATFORM_PUBLIC_URLS`

```text
/
/health
/ready
/ws/**
/api/public/**
```

`IGNORE_URLS`, `ACTUATOR_PROBE_URLS`, `AUTHENTICATED_URLS`, and
`ALL_MANAGER_PERMISSION` are unchanged.

## 6. Tests

Existing coverage that must keep passing unchanged:

- `AuthControllerIT` — exercises the public register / verify-email /
  resend-verification / forgot-password / reset-password endpoints end to end
  against real Postgres, Redis, and RabbitMQ. Primary regression net for §4.1.
- `SecurityConfigurationIT` — filter positions relative to
  `BearerTokenAuthenticationFilter`. Primary regression net for §4.2.
- `ActuatorSecurityIT` — actuator probe, Prometheus, and deny rules.
- `JwksControllerTest`, `CustomAuthenticationFilterTest`, the two controller
  contract tests.

New tests, written before the corresponding move:

- `AuthSecurityContributorTest` — `publicPaths()` returns exactly the nine
  paths in §5.1. Guards against a route silently dropping out of the public set
  during the move.
- `SecurityConfigurationIT` additions — a contributed public path
  (`POST /api/v1/auth/login`) is reachable without a bearer token; an
  `/api/**` path that no contributor declares returns 401 without one.

Updated:

- `OpenApiConfigurationTest` — package move plus the
  `Class.forName("com.vandunxg.file_processing.configuration.OpenApiConfiguration")`
  string.

Verification gate: `./mvnw spotless:apply && ./mvnw verify`.

## 7. Deliberately excluded

- The OpenAPI title `"File Processing Auth API"` stays. It is an asserted
  contract; renaming it is a product decision, not a refactor.
- Public paths stay hardcoded rather than derived from
  `${app.api.prefix}/${app.api.version}`. They are hardcoded today; changing
  the resolution mechanism is separate work with its own risk.
- `fileimport` is untouched, including its legacy `adapter/in|out` packages and
  its import of `configuration.security.AuthenticatedUser`.
- `ActionLoggingFilter`'s skip regex is `\/api\/certificate\/.well-known\/jwks\.json`
  while the controller serves `/api/v1/certificate/.well-known` — the pattern
  cannot match. It is also unreachable today because `web.ignoring()` covers
  that path. Reported as a finding, not fixed here.

## 8. Compliance

| ARCHITECTURE.md checklist item | Result |
|---|---|
| Which module owns this behavior? | `auth` owns its routes, JWT mechanism, and email wiring; `configuration` owns the chain skeleton and platform rules |
| Does any module import another module's infrastructure? | No; the root depends on `ModuleSecurityContributor`, which modules implement |
| Is every new interface justified by a real boundary? | One interface, extended rather than added, protecting the composition root from module internals |
| Is the change extending legacy Hexagonal structure? | No new `adapter`/`port`/`UseCase`/`Port`/`Adapter` names |
| Business behavior changed | NONE |
| Security semantics changed | NONE |
| Database or migration changed | NONE |
