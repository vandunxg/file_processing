# Auth Configuration Validation Design

## Goal

Make invalid auth configuration fail during application startup rather than when an authentication,
token, email, Redis, or AMQP flow is first exercised.

## Scope

`AuthProperties` remains the single `@ConfigurationProperties(prefix = "app.auth")` record. The
change validates all nested auth configuration, including password policy, registration and login
throttles, refresh/session lifetimes, bootstrap values, JWT signing and verification material,
email verification and password reset settings, cleanup, Redis keys and windows, and AMQP exchange,
routing keys, and queues.

No valid configured value changes and no new configuration namespace is introduced.

## Design

Add `@Validated` to `AuthProperties`, `@Valid` to each nested record component, and Jakarta
constraints at the field that owns each invariant:

- Strings that identify a required issuer, audience, key id, key material, URL, exchange, route,
  queue, or Redis key prefix use `@NotBlank`.
- Durations used as TTLs, windows, cadences, locks, skews, or reuse detection use a positive
  duration constraint.
- Numeric limits, batch sizes, bcrypt cost, and password bounds use positive numeric constraints.
- `Password` retains a compact constructor for the cross-field rule `maxLength >= minLength`.

Nested records receive `@Valid` at each containing component so validation reaches every level,
including `Bootstrap.Admin`, JWT public keys, Redis key groups, and AMQP route/queue groups.

## Failure Behavior

Spring Boot property binding rejects invalid `app.auth` values while creating `AuthProperties`.
The application context fails to start with a configuration-properties bind error. No controller or
authentication flow runs with malformed or unsafe settings.

## Testing

Use `ApplicationContextRunner` with `@EnableConfigurationProperties(AuthProperties.class)` and a
complete valid fixture. Verify that the fixture binds, and independently verify startup failure for:

- blank JWT issuer or signing key material;
- zero or negative token/window durations;
- non-positive rate limits, batch sizes, bcrypt cost, or password bounds;
- `maxLength < minLength`;
- blank Redis, AMQP, and email URL configuration.

The tests assert context startup state, preserving the actual Spring Boot binding path rather than
testing annotations in isolation.

## Out Of Scope

- Renaming auth properties or changing valid defaults.
- Adding a new configuration module or property source.
- Transactional outbox delivery work from Phase 6 onward.
