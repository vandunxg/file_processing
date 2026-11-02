---
name: testing
description: Use when writing or fixing tests, choosing between unit, slice, and integration tests, setting up Testcontainers, naming test methods, or dealing with flaky or time-dependent tests in a Spring Boot project.
metadata:
  version: "0.0.1"
  author: vandunxg
  scope: shared
  category: testing
  stack: java-21, spring-boot-4
---

# Testing

Tests protect observable behavior and production risk, not a coverage number.

## Test levels

| Need                                         | Use |
|----------------------------------------------|-----|
| Domain / application logic                   | plain JUnit 5 + Mockito + AssertJ |
| Controller contract                          | `@WebMvcTest` slice |
| Repository behavior                          | `@DataJpaTest` or focused test on PostgreSQL Testcontainers |
| SQL, migration, locking, index behavior      | PostgreSQL Testcontainers (`@ServiceConnection`), never in-memory DB |
| Full wiring only                             | `@SpringBootTest` |

Spring Boot 4: `@MockitoBean`/`@MockitoSpyBean`, not `@MockBean`/`@SpyBean`;
`RestTestClient` (`@AutoConfigureRestTestClient`) for full-stack HTTP tests.
Assert the common `Response<T>` envelope and module error codes, not Problem
Details.

## Spring test mechanics

- `@WebMvcTest` creates no services or repositories: every controller
  dependency needs a `@MockitoBean`. `@Import` the security configuration so the
  slice enforces the real rules.
- Share one container through a test configuration:

```java
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestcontainersConfiguration {
  @Bean
  @ServiceConnection
  PostgreSQLContainer<?> postgres() {
    return new PostgreSQLContainer<>("postgres:16-alpine");
  }
}
```

- Keep the `@MockitoBean` set and active profiles identical across ITs; each
  distinct set builds a new context (and new containers).
- `@Testcontainers(disabledWithoutDocker = true)` skips silently — confirm the
  report shows the ITs actually ran.
- Prove constraints directly: a raw `jdbcTemplate.update(...)` violating them
  throws `DataIntegrityViolationException`.
- Stale version: two updates with the same `version`; the second returns `409`.
- Test profile may use a cheap password hash (`bcrypt-cost: 4`).

## What must be tested when changed

- authorization and ownership;
- state transitions;
- soft delete and exclusion of deleted rows; partial uniqueness after delete;
- idempotency and duplicate delivery;
- optimistic locking and atomic claims;
- file limits, malformed input, cleanup;
- transaction rollback and after-commit behavior;
- timeout, retry classification, fallback;
- error-code uniqueness and i18n key completeness.

Pure refactors need no artificial tests.

## Naming and determinism

```text
delete_throwsRoleIsActive_whenRoleIsStillActive()
findById_returnsEmpty_whenRoleWasSoftDeleted()
```

- No hidden sleeps. Inject `Clock`, control executor completion, and wait on
  observable conditions.
- Never mix a fixed test instant with the application's real `Clock`: data
  derived from a hard-coded `NOW` (deadlines, expiries) silently expires once
  real time passes it. Derive such values from the injected `Clock`.
- Unit test class `<ClassName>Test`; integration test `<ClassName>IT`.
