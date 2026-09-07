# Configuration Module DDD Migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the technical configuration and route knowledge that belongs to the `auth` module out of the application-wide `configuration` module, so the composition root owns only platform-level concerns.

**Architecture:** `configuration` is the composition root, not a business module. It keeps the security chain skeleton, platform public paths, actuator rules, and method-security wiring. Everything a single module owns — its email wiring, its unauthenticated routes, its authentication mechanism, its filters — moves into that module and reaches the chain through one extension point, `ModuleSecurityContributor`, which the root consumes without importing any module package.

**Tech Stack:** Java 21, Spring Boot 4.1.x, Spring Security 7.1, JUnit 5, Mockito, AssertJ, Testcontainers, Maven wrapper.

**Spec:** [`docs/superpowers/specs/2026-09-07-configuration-module-ddd-design.md`](../specs/2026-09-07-configuration-module-ddd-design.md)

## Global Constraints

- This is a behavior-neutral refactor. Business behavior, HTTP contracts, security semantics, and database schema **MUST NOT** change.
- No new Hexagonal ceremony: no `adapter/in|out`, `port/in|out`, `*UseCase`, `*RepositoryPort`, `*PersistenceAdapter` names.
- `configuration` **MUST NOT** import from `auth` or `fileimport` after Task 4.
- Module-contributed public paths **MUST** feed both `authorizeHttpRequests(...).permitAll()` and `webSecurityCustomizer()`'s `web.ignoring()`. Feeding only the first silently starts running four filters on the auth endpoints. See spec §4.1.
- Public path strings are copied **verbatim** from today's `SecurityConfiguration.PUBLIC_URLS`. Do not switch them to `${app.api.prefix}/${app.api.version}` — out of scope, spec §7.
- Do not touch `fileimport`, the OpenAPI title `"File Processing Auth API"`, or `ActionLoggingFilter`.
- Formatting gate after every task: `./mvnw spotless:apply` before committing.

## Environment note — read before starting

`docker info` fails on the machine where this plan was written, so every
Testcontainers integration test (`*IT.java`, run by failsafe during
`./mvnw verify`) **cannot execute here**. The verified baseline is:

```text
./mvnw -o -DskipTests compile   -> BUILD SUCCESS
./mvnw -o test                  -> Tests run: 153, Failures: 0, Errors: 0
```

Tasks 3 and 4 change security wiring whose primary regression net is
`AuthControllerIT`, `SecurityConfigurationIT`, and `ActuatorSecurityIT`. If
Docker is still unavailable when you execute those tasks:

1. Run `./mvnw -o test` (surefire only) as the per-step gate.
2. Do **not** claim the task verified. Record in the commit body that ITs did
   not run, and report it at the end so a human can run `./mvnw verify` with
   Docker up.

Where a step below says "Run the IT", it means `./mvnw verify -Dit.test=<Class> -DfailIfNoTests=false` (which also runs the unit suite). Skip and flag if Docker is down.

## File Structure

| File | Responsibility | Task |
|---|---|---|
| `configuration/openapi/OpenApiConfiguration.java` | OpenAPI document + bearer scheme (moved, unchanged body) | 1 |
| `configuration/serialization/InstantEpochMillisSerializer.java` | Jackson `Instant` → epoch millis (moved, unchanged body) | 1 |
| `auth/infrastructure/config/EmailConfiguration.java` | `MailService`/`MailSenderFactory` beans (moved from `configuration`) | 2 |
| `configuration/security/ModuleSecurityContributor.java` | Extension point: a module's public paths + chain contributions (replaces `SecurityFilterChainContributor`) | 3 |
| `auth/infrastructure/security/AuthSecurityContributor.java` | Auth's public paths, JWT resource server, filters (replaces `AuthSecurityFilterChainContributor`) | 3, 4 |
| `configuration/security/SecurityConfiguration.java` | Chain skeleton, platform public paths, actuator rules, method security, auditor | 2, 3, 4 |
| `configuration/security/AuthenticatedUser.java` | Shared principal contract — **unchanged, does not move** | — |

---

### Task 1: Group `configuration` by capability

Moves two files into concern-named packages. `OpenApiConfiguration` and `InstantEpochMillisSerializer` sit flat at the module root today, next to unrelated things; `serialization/` also makes clear that `InstantEpochMillisSerializer` is a `@JacksonComponent`, not configuration.

Nothing imports either class by name — the only reference is a `Class.forName` string in the test, which is why the test moves first and gives a real red.

**Files:**
- Create: `src/main/java/com/vandunxg/file_processing/configuration/openapi/OpenApiConfiguration.java`
- Delete: `src/main/java/com/vandunxg/file_processing/configuration/OpenApiConfiguration.java`
- Create: `src/main/java/com/vandunxg/file_processing/configuration/serialization/InstantEpochMillisSerializer.java`
- Delete: `src/main/java/com/vandunxg/file_processing/configuration/InstantEpochMillisSerializer.java`
- Create: `src/test/java/com/vandunxg/file_processing/configuration/openapi/OpenApiConfigurationTest.java`
- Delete: `src/test/java/com/vandunxg/file_processing/configuration/OpenApiConfigurationTest.java`

**Interfaces:**
- Consumes: nothing from earlier tasks.
- Produces: no new types. Later tasks rely only on `configuration/security/` being untouched by this task.

- [ ] **Step 1: Move the test and point it at the new package (this is the failing test)**

```bash
git mv src/test/java/com/vandunxg/file_processing/configuration/OpenApiConfigurationTest.java \
       src/test/java/com/vandunxg/file_processing/configuration/openapi/OpenApiConfigurationTest.java
```

Then change exactly two lines in the moved file. Line 1:

```java
package com.vandunxg.file_processing.configuration.openapi;
```

and the `Class.forName` argument inside `configurationClass()`:

```java
  private static Class<?> configurationClass() {
    try {
      return Class.forName(
          "com.vandunxg.file_processing.configuration.openapi.OpenApiConfiguration");
    } catch (ClassNotFoundException ignored) {
      return null;
    }
  }
```

Leave both `@Test` methods exactly as they are.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -o test -Dtest=OpenApiConfigurationTest`

Expected: FAIL. `configurationClass()` swallows the `ClassNotFoundException` and returns `null`, so `declaresBearerAuthenticationAsTheDefaultApiSecurityRequirement` fails on
`assertThat(configuration).isNotNull()` with `Expecting actual not to be null`.

- [ ] **Step 3: Move the two main sources**

```bash
mkdir -p src/main/java/com/vandunxg/file_processing/configuration/openapi \
         src/main/java/com/vandunxg/file_processing/configuration/serialization
git mv src/main/java/com/vandunxg/file_processing/configuration/OpenApiConfiguration.java \
       src/main/java/com/vandunxg/file_processing/configuration/openapi/OpenApiConfiguration.java
git mv src/main/java/com/vandunxg/file_processing/configuration/InstantEpochMillisSerializer.java \
       src/main/java/com/vandunxg/file_processing/configuration/serialization/InstantEpochMillisSerializer.java
```

Change only the `package` line in each. `OpenApiConfiguration.java` line 1:

```java
package com.vandunxg.file_processing.configuration.openapi;
```

`InstantEpochMillisSerializer.java` line 1:

```java
package com.vandunxg.file_processing.configuration.serialization;
```

Both classes stay component-scanned: `FileProcessingApplication` scans `com.vandunxg.file_processing.*`, which covers any depth under `configuration`.

- [ ] **Step 4: Run the test to verify it passes**

Run: `./mvnw -o test -Dtest=OpenApiConfigurationTest`
Expected: PASS, `Tests run: 2, Failures: 0, Errors: 0`

- [ ] **Step 5: Run the full unit suite**

Run: `./mvnw -o test`
Expected: `Tests run: 153, Failures: 0, Errors: 0` — same count as the baseline.

- [ ] **Step 6: Commit**

```bash
./mvnw spotless:apply
git add -A src/main/java/com/vandunxg/file_processing/configuration \
           src/test/java/com/vandunxg/file_processing/configuration
git commit -m "refactor(configuration): group application config by capability

OpenApiConfiguration and InstantEpochMillisSerializer sat flat at the
module root next to unrelated concerns. Move them into openapi/ and
serialization/, which also names InstantEpochMillisSerializer for what
it is: a Jackson component, not configuration."
```

---

### Task 2: `auth` reclaims its email wiring; drop the dead password encoder bean

`EmailConfiguration` lives in application-wide configuration but its only consumer is `auth/infrastructure/email/MailServiceEmailSender`. Move it into the module that uses it.

`SecurityConfiguration.passwordEncoder()` is dead, not misplaced: `BcryptPasswordHasher` builds its own `DelegatingPasswordEncoder` from `AuthProperties.password().bcryptCost()` and injects nothing, and `common-web` never references `PasswordEncoder`. Moving a bean nobody injects into `auth` would just relocate dead code, so delete it.

This task has **no new test**. Its gate is that the context still loads: the auth integration tests construct `MailServiceEmailSender`, which fails fast if the `MailService` bean disappears. Adding a bean-presence unit test here would assert the Spring container works, not that this change is correct.

**Files:**
- Create: `src/main/java/com/vandunxg/file_processing/auth/infrastructure/config/EmailConfiguration.java`
- Delete: `src/main/java/com/vandunxg/file_processing/configuration/EmailConfiguration.java`
- Modify: `src/main/java/com/vandunxg/file_processing/configuration/security/SecurityConfiguration.java` (remove `passwordEncoder()` and its two imports)

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: `MailService` and `MailSenderFactory` beans, now declared in `auth.infrastructure.config`. Bean names, types, and `@ConditionalOnMissingBean` conditions are unchanged, so no consumer changes.

- [ ] **Step 1: Confirm the two claims before deleting anything**

```bash
grep -rn "MailService\|MailSenderFactory" --include=*.java src/ \
  | grep -v "configuration/EmailConfiguration"
grep -rn "PasswordEncoder" --include=*.java src/
```

Expected: the first prints only `auth/infrastructure/email/MailServiceEmailSender.java`. The second prints only `SecurityConfiguration.java` (the declaration) and `BcryptPasswordHasher.java` / `PasswordHasher.java` (which construct their own). No injection site for the bean. If either expectation does not hold, stop and report — the premise of this task is wrong.

- [ ] **Step 2: Move `EmailConfiguration` into `auth`**

```bash
git mv src/main/java/com/vandunxg/file_processing/configuration/EmailConfiguration.java \
       src/main/java/com/vandunxg/file_processing/auth/infrastructure/config/EmailConfiguration.java
```

Change only the `package` line:

```java
package com.vandunxg.file_processing.auth.infrastructure.config;
```

The class body — both `@Bean` methods and their `@ConditionalOnMissingBean` annotations — stays byte-for-byte identical.

- [ ] **Step 3: Delete the dead `passwordEncoder()` bean**

In `configuration/security/SecurityConfiguration.java`, delete this method:

```java
  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }
```

and these two now-unused imports:

```java
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
```

Leave `springSecurityAuditorAware()` and `expressionHandler()` alone.

- [ ] **Step 4: Verify it compiles and the unit suite is unchanged**

Run: `./mvnw -o test`
Expected: `BUILD SUCCESS`, `Tests run: 153, Failures: 0, Errors: 0`

- [ ] **Step 5: Run the auth integration test that loads the real context**

Run: `./mvnw verify -Dit.test=AuthControllerIT -DfailIfNoTests=false`
Expected: PASS — this is what proves the `MailService` bean is still resolvable after the move.

If Docker is unavailable, skip and record it per the environment note above.

- [ ] **Step 6: Commit**

```bash
./mvnw spotless:apply
git add -A src/main/java/com/vandunxg/file_processing
git commit -m "refactor(auth): own the email wiring, drop the dead password encoder

EmailConfiguration sat in application-wide configuration although
MailServiceEmailSender is its only consumer. SecurityConfiguration's
passwordEncoder() bean had no consumer at all: BcryptPasswordHasher
builds its own DelegatingPasswordEncoder and common-web never injects
a PasswordEncoder, so delete it rather than relocate dead code."
```

---

### Task 3: `auth` declares its own public routes

`SecurityConfiguration.PUBLIC_URLS` hardcodes nine routes the `auth` module serves. The composition root should not know a module's URL contract — the same reason `SecurityFilterChainContributor` already exists.

Widen that extension point instead of adding a second one, and rename both types to match their broader job.

**Read spec §4.1 before writing Step 5.** The contributed paths must reach *both* `authorizeHttpRequests(...).permitAll()` and `web.ignoring()`. `web.ignoring()` bypasses the entire filter chain, so today `/api/v1/auth/login` never reaches `ForbiddenTokenFilter`, `NoHandlerFoundFilter`, `CustomAuthenticationFilter`, or `ActionLoggingFilter`. Wiring only the first call site would start running all four on the auth endpoints — a behavior change disguised as a refactor.

**Files:**
- Create: `src/main/java/com/vandunxg/file_processing/configuration/security/ModuleSecurityContributor.java`
- Delete: `src/main/java/com/vandunxg/file_processing/configuration/security/SecurityFilterChainContributor.java`
- Create: `src/main/java/com/vandunxg/file_processing/auth/infrastructure/security/AuthSecurityContributor.java`
- Delete: `src/main/java/com/vandunxg/file_processing/auth/infrastructure/security/AuthSecurityFilterChainContributor.java`
- Modify: `src/main/java/com/vandunxg/file_processing/configuration/security/SecurityConfiguration.java`
- Create: `src/test/java/com/vandunxg/file_processing/auth/infrastructure/security/AuthSecurityContributorTest.java`
- Modify: `src/test/java/com/vandunxg/file_processing/configuration/security/SecurityConfigurationIT.java`

**Interfaces:**
- Consumes: nothing from Tasks 1–2.
- Produces:
  - `ModuleSecurityContributor` with `default List<String> publicPaths()` returning `List.of()` and `default void contribute(HttpSecurity http) throws Exception` doing nothing.
  - `AuthSecurityContributor implements ModuleSecurityContributor`, `public static final int ORDER = 100`, constructor `(JwtDecoder, Converter<Jwt, AbstractAuthenticationToken>, CustomAuthenticationFilter, ActionLoggingFilter)` generated by Lombok `@RequiredArgsConstructor` in that field order. Task 4 adds the resource-server call to its `contribute()`.
  - `SecurityConfiguration.PLATFORM_PUBLIC_URLS` (private) replaces `PUBLIC_URLS`.

- [ ] **Step 1: Write the failing test**

Create `src/test/java/com/vandunxg/file_processing/auth/infrastructure/security/AuthSecurityContributorTest.java`:

Its constructor is two-arg at this point; Task 4 widens it to four.

```java
package com.vandunxg.file_processing.auth.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.vandunxg.file_processing.configuration.security.ModuleSecurityContributor;
import org.junit.jupiter.api.Test;

class AuthSecurityContributorTest {

  private final ModuleSecurityContributor contributor =
      new AuthSecurityContributor(
          mock(CustomAuthenticationFilter.class), mock(ActionLoggingFilter.class));

  /**
   * The composition root no longer knows these routes, so this list is the only place that records
   * which auth endpoints are reachable without a bearer token. A path silently dropping out of it
   * would make a public endpoint start returning 401.
   */
  @Test
  void declaresEveryAuthEndpointServedWithoutAuthentication() {
    assertThat(contributor.publicPaths())
        .containsExactly(
            "/api/v1/auth/register",
            "/api/v1/auth/verify-email",
            "/api/v1/auth/resend-verification",
            "/api/v1/auth/forgot-password",
            "/api/v1/auth/reset-password",
            "/api/v1/auth/complete-password-change",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/certificate/.well-known/jwks.json");
  }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -o test -Dtest=AuthSecurityContributorTest`

Expected: FAIL at compilation — `cannot find symbol: class AuthSecurityContributor` and `cannot find symbol: class ModuleSecurityContributor`.

- [ ] **Step 3: Create the widened extension point**

Create `src/main/java/com/vandunxg/file_processing/configuration/security/ModuleSecurityContributor.java`:

```java
package com.vandunxg.file_processing.configuration.security;

import java.util.List;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;

/**
 * Extension point a business module implements to declare what it needs from the application-wide
 * security chain.
 *
 * <p>It exists so the composition root never imports a module's {@code infrastructure} package and
 * never hardcodes a module's routes: the module owns its public endpoints, its authentication
 * mechanism, and its filters, while the root only decides where in the chain module contributions
 * are applied. Order several contributors with {@code @Order}. Ordering affects {@link
 * #contribute(HttpSecurity)} only — {@link #publicPaths()} results are unioned.
 */
public interface ModuleSecurityContributor {

  /**
   * Request path patterns this module serves without authentication. The root both permits these
   * paths and excludes them from the security filter chain.
   */
  default List<String> publicPaths() {
    return List.of();
  }

  /** Installs this module's authentication mechanism and request filters. */
  default void contribute(HttpSecurity http) throws Exception {}
}
```

Then delete the old interface:

```bash
git rm src/main/java/com/vandunxg/file_processing/configuration/security/SecurityFilterChainContributor.java
```

- [ ] **Step 4: Rename the auth contributor and give it the paths**

```bash
git mv src/main/java/com/vandunxg/file_processing/auth/infrastructure/security/AuthSecurityFilterChainContributor.java \
       src/main/java/com/vandunxg/file_processing/auth/infrastructure/security/AuthSecurityContributor.java
```

Replace the whole file with:

```java
package com.vandunxg.file_processing.auth.infrastructure.security;

import java.util.List;

import com.vandunxg.file_processing.configuration.security.ModuleSecurityContributor;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.stereotype.Component;

/**
 * Declares what the auth module installs into the application-wide security chain: the endpoints it
 * serves anonymously, and its own request filters — credential-version resolution first, then
 * action logging, both after bearer-token authentication has populated the security context.
 */
@NullMarked
@Component
@Order(AuthSecurityContributor.ORDER)
@RequiredArgsConstructor
public class AuthSecurityContributor implements ModuleSecurityContributor {

  /**
   * Auth contributes first: every other module's filters can then assume the security context has
   * already been resolved to a real user.
   */
  public static final int ORDER = 100;

  /** Copied verbatim from the composition root's former {@code PUBLIC_URLS}. */
  private static final List<String> PUBLIC_PATHS =
      List.of(
          "/api/v1/auth/register",
          "/api/v1/auth/verify-email",
          "/api/v1/auth/resend-verification",
          "/api/v1/auth/forgot-password",
          "/api/v1/auth/reset-password",
          "/api/v1/auth/complete-password-change",
          "/api/v1/auth/login",
          "/api/v1/auth/refresh",
          "/api/v1/certificate/.well-known/jwks.json");

  private final CustomAuthenticationFilter customAuthenticationFilter;
  private final ActionLoggingFilter actionLoggingFilter;

  @Override
  public List<String> publicPaths() {
    return PUBLIC_PATHS;
  }

  @Override
  public void contribute(HttpSecurity http) {
    http.addFilterAfter(customAuthenticationFilter, BearerTokenAuthenticationFilter.class);
    http.addFilterAfter(actionLoggingFilter, CustomAuthenticationFilter.class);
  }
}
```

The two constructor fields match the two-arg form Step 1's test already uses. Task 4 adds `JwtDecoder` and the converter ahead of them.

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw -o test -Dtest=AuthSecurityContributorTest`
Expected: PASS, `Tests run: 1, Failures: 0, Errors: 0`

- [ ] **Step 6: Consume the contributed paths in the composition root**

In `configuration/security/SecurityConfiguration.java`:

Replace the `PUBLIC_URLS` constant with the platform-only list (the five entries that are not any module's):

```java
  /**
   * Endpoints the application itself serves without authentication. A module's own public routes
   * are not listed here — it declares them through {@link ModuleSecurityContributor#publicPaths()}.
   */
  private static final String[] PLATFORM_PUBLIC_URLS = {
    "/", "/health", "/ready", "/ws/**", "/api/public/**"
  };
```

Rename the contributor field:

```java
  /**
   * Optional on purpose: a context that loads this configuration without any business module on the
   * scan path should build a chain with no module contributions, not fail to start on a missing
   * bean.
   */
  private final ObjectProvider<ModuleSecurityContributor> moduleSecurityContributors;
```

Replace the body of `filterChain` down to `return http.build();` with:

```java
  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    // orderedStream() honours @Order on each contributor, so filter placement does not depend on
    // classpath scan order once a second module starts contributing.
    List<ModuleSecurityContributor> contributors =
        moduleSecurityContributors.orderedStream().toList();
    String[] modulePublicUrls = publicUrlsOf(contributors);

    JwtAuthenticationProvider jwtAuthenticationProvider = new JwtAuthenticationProvider(jwtDecoder);
    jwtAuthenticationProvider.setJwtAuthenticationConverter(jwtAuthenticationConverter);

    http.csrf(AbstractHttpConfigurer::disable)
        .sessionManagement(
            sessionAuthenticationStrategy ->
                sessionAuthenticationStrategy.sessionCreationPolicy(
                    SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            authorize -> {
              authorize
                  .requestMatchers(HttpMethod.OPTIONS, "/**")
                  .permitAll()
                  .requestMatchers(IGNORE_URLS)
                  .permitAll()
                  .requestMatchers(PLATFORM_PUBLIC_URLS)
                  .permitAll();
              if (modulePublicUrls.length > 0) {
                authorize.requestMatchers(modulePublicUrls).permitAll();
              }
              authorize
                  .requestMatchers(ACTUATOR_PROBE_URLS)
                  .permitAll()
                  .requestMatchers("/actuator/prometheus")
                  .hasAuthority(ALL_MANAGER_PERMISSION)
                  .requestMatchers("/actuator/**")
                  .denyAll()
                  .requestMatchers(AUTHENTICATED_URLS)
                  .authenticated();
            })
        .oauth2ResourceServer(
            oauth2 ->
                oauth2.authenticationManagerResolver(
                    request -> jwtAuthenticationProvider::authenticate))
        .exceptionHandling(
            exHandling -> exHandling.authenticationEntryPoint(this.customAuthenticationEntryPoint));

    http.addFilterAfter(noHandlerFoundFilter, BearerTokenAuthenticationFilter.class);
    http.addFilterAfter(forbiddenTokenFilter, BearerTokenAuthenticationFilter.class);
    for (ModuleSecurityContributor contributor : contributors) {
      contributor.contribute(http);
    }

    return http.build();
  }
```

The platform matchers stay ahead of the module matchers, which stay ahead of the actuator rules — the same relative order as today's single `PUBLIC_URLS` array (platform entries first, auth entries last). The `oauth2ResourceServer` call is still here; Task 4 moves it.

Replace `webSecurityCustomizer()` with:

```java
  @Bean
  WebSecurityCustomizer webSecurityCustomizer() {
    // Contributors are resolved inside the lambda, not at bean construction: WebSecurity applies
    // customizers while building the same context that builds the filter chain, so this touches
    // contributor beans no earlier than the chain already does.
    return web -> {
      WebSecurity.IgnoredRequestConfigurer ignoring =
          web.ignoring().requestMatchers(PLATFORM_PUBLIC_URLS);
      String[] modulePublicUrls = publicUrlsOf(moduleSecurityContributors.orderedStream().toList());
      if (modulePublicUrls.length > 0) {
        ignoring = ignoring.requestMatchers(modulePublicUrls);
      }
      ignoring.requestMatchers(IGNORE_URLS);
    };
  }

  private static String[] publicUrlsOf(List<ModuleSecurityContributor> contributors) {
    return contributors.stream()
        .flatMap(contributor -> contributor.publicPaths().stream())
        .distinct()
        .toArray(String[]::new);
  }
```

`web.ignoring()` returns `WebSecurity.IgnoredRequestConfigurer`, which extends `AbstractRequestMatcherRegistry<IgnoredRequestConfigurer>`, so `requestMatchers(String...)` returns the same configurer and chains. The `length > 0` guards avoid passing an empty pattern array when no module contributes.

Add these imports:

```java
import java.util.List;

import org.springframework.security.config.annotation.web.builders.WebSecurity;
```

`java.util.List` goes in the existing `java.*` import block at the top; the Spring import goes in alphabetical position among the other `org.springframework.security.config.annotation.web.*` imports. `./mvnw spotless:apply` will fix ordering if you get it wrong.

- [ ] **Step 7: Add the integration assertions**

In `src/test/java/com/vandunxg/file_processing/configuration/security/SecurityConfigurationIT.java`, add `@AutoConfigureMockMvc` to the class and two tests. The full file becomes:

```java
package com.vandunxg.file_processing.configuration.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.vandunxg.file_processing.auth.infrastructure.security.ActionLoggingFilter;
import com.vandunxg.file_processing.auth.infrastructure.security.CustomAuthenticationFilter;
import com.vandunxg.file_processing.testsupport.AuthIntegrationTestBase;
import com.vandunxg.file_processing.testsupport.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.web.servlet.MockMvc;

@PostgresIntegrationTest
@AutoConfigureMockMvc
class SecurityConfigurationIT extends AuthIntegrationTestBase {

  @Autowired private FilterChainProxy filterChainProxy;
  @Autowired private ActionLoggingFilter actionLoggingFilter;
  @Autowired private CustomAuthenticationFilter customAuthenticationFilter;
  @Autowired private MockMvc mockMvc;

  @Test
  void runsActionLoggingAfterBearerAuthentication() {
    var filters = filterChainProxy.getFilters("/api/v1/me");
    int bearerFilterIndex =
        filters.indexOf(
            filters.stream()
                .filter(BearerTokenAuthenticationFilter.class::isInstance)
                .findFirst()
                .orElseThrow());

    assertThat(filters.indexOf(actionLoggingFilter)).isGreaterThan(bearerFilterIndex);
  }

  @Test
  void runsCustomAuthenticationAfterBearerAuthentication() {
    var filters = filterChainProxy.getFilters("/api/v1/me");
    int bearerFilterIndex =
        filters.indexOf(
            filters.stream()
                .filter(BearerTokenAuthenticationFilter.class::isInstance)
                .findFirst()
                .orElseThrow());

    assertThat(filters.indexOf(customAuthenticationFilter)).isGreaterThan(bearerFilterIndex);
  }

  /**
   * A path the auth module contributed through {@code publicPaths()} must stay outside the security
   * filter chain entirely, exactly as the composition root's former hardcoded list left it.
   */
  @Test
  void excludesAContributedPublicPathFromTheSecurityFilterChain() {
    assertThat(filterChainProxy.getFilters("/api/v1/auth/login")).isEmpty();
  }

  /** A path no module contributed still needs a bearer token. */
  @Test
  void requiresAuthenticationForPathsNoModuleContributed() throws Exception {
    mockMvc.perform(get("/api/v1/me")).andExpect(status().isUnauthorized());
  }
}
```

`WebSecurity.ignoring()` registers a `SecurityFilterChain` with an empty filter list, so `getFilters("/api/v1/auth/login")` returning empty is the direct, precise assertion of spec §4.1 — it proves the path bypasses the chain rather than merely being permitted. That makes an HTTP-level check on `/auth/login` redundant here; `AuthControllerIT` already owns the endpoint's behavior.

- [ ] **Step 8: Run the integration test**

Run: `./mvnw verify -Dit.test=SecurityConfigurationIT -DfailIfNoTests=false`
Expected: PASS, 4 tests.

Then the endpoint regression net:

Run: `./mvnw verify -Dit.test=AuthControllerIT -DfailIfNoTests=false`
Expected: PASS — every public auth endpoint still reachable without a token.

If Docker is unavailable, run `./mvnw -o test` instead and flag both as unverified.

- [ ] **Step 9: Commit**

```bash
./mvnw spotless:apply
git add -A src/main/java src/test/java
git commit -m "refactor(security): let modules declare their own public routes

SecurityConfiguration hardcoded nine /api/v1/auth and JWKS routes, so
the composition root knew the auth module's URL contract. Widen the
existing contributor extension point with publicPaths() instead of
adding a second one, and feed the result to both the permitAll rules
and web.ignoring() so the endpoints keep bypassing the filter chain
exactly as before."
```

---

### Task 4: `auth` reclaims the OAuth2 resource server

`SecurityConfiguration` builds the `JwtAuthenticationProvider` from `JwtDecoder` and `jwtAuthenticationConverter` — both produced by `auth/infrastructure/config/JwtConfiguration`. Deciding *how a request is authenticated* is the auth module's job; the root only decides where module contributions land.

**Why the chain does not change shape:** `addFilterAfter(filter, BearerTokenAuthenticationFilter.class)` takes its position from the static `FilterOrderRegistration` table (verified: `BearerTokenAuthenticationFilter` is registered there), not from the order in which configurers ran. So calling `oauth2ResourceServer(...)` from a contributor — after the root has already added `NoHandlerFoundFilter` and `ForbiddenTokenFilter` — produces the same ordering. `SecurityConfigurationIT` is the guard.

**Fail-closed check:** if no module contributes a resource server, the chain has no authentication mechanism, `/api/**` stays `.authenticated()`, and every non-public request is rejected. The failure mode is closed.

`CustomAuthenticationEntryPoint` stays at the root deliberately: it answers how the application responds to an unauthenticated request, independent of which module authenticates.

**Files:**
- Modify: `src/main/java/com/vandunxg/file_processing/auth/infrastructure/security/AuthSecurityContributor.java`
- Modify: `src/main/java/com/vandunxg/file_processing/configuration/security/SecurityConfiguration.java`
- Modify: `src/test/java/com/vandunxg/file_processing/auth/infrastructure/security/AuthSecurityContributorTest.java`
- Modify: `src/test/java/com/vandunxg/file_processing/configuration/security/SecurityConfigurationIT.java`

**Interfaces:**
- Consumes: `ModuleSecurityContributor` and `AuthSecurityContributor` from Task 3.
- Produces: `AuthSecurityContributor`'s constructor becomes four-arg — `(JwtDecoder, Converter<Jwt, AbstractAuthenticationToken>, CustomAuthenticationFilter, ActionLoggingFilter)`, in that field order. `SecurityConfiguration` loses its `jwtDecoder` and `jwtAuthenticationConverter` fields.

- [ ] **Step 1: Write the failing test**

Add to `SecurityConfigurationIT`:

```java
  /**
   * The root no longer installs an authentication mechanism; auth contributes it. If that
   * contribution is lost, /api/** is still .authenticated() so the app fails closed — this asserts
   * it did not silently fail closed.
   */
  @Test
  void installsBearerTokenAuthenticationThroughAModuleContribution() {
    assertThat(filterChainProxy.getFilters("/api/v1/me"))
        .anyMatch(BearerTokenAuthenticationFilter.class::isInstance);
  }
```

This one passes before the change too — it is a pin, not a red. The real red is in the unit test: update `AuthSecurityContributorTest`'s field to the four-arg constructor the task produces:

```java
  @SuppressWarnings("unchecked")
  private final ModuleSecurityContributor contributor =
      new AuthSecurityContributor(
          mock(JwtDecoder.class),
          mock(Converter.class),
          mock(CustomAuthenticationFilter.class),
          mock(ActionLoggingFilter.class));
```

and re-add its two imports:

```java
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.oauth2.jwt.JwtDecoder;
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -o test -Dtest=AuthSecurityContributorTest`
Expected: FAIL at compilation — `constructor AuthSecurityContributor in class AuthSecurityContributor cannot be applied to given types` (it still takes two arguments).

- [ ] **Step 3: Move the resource-server wiring into the contributor**

In `AuthSecurityContributor.java`, add the two dependencies **above** the existing filter fields so Lombok generates the documented constructor order:

```java
  private final JwtDecoder jwtDecoder;
  private final Converter<Jwt, AbstractAuthenticationToken> jwtAuthenticationConverter;
  private final CustomAuthenticationFilter customAuthenticationFilter;
  private final ActionLoggingFilter actionLoggingFilter;
```

Replace `contribute` with:

```java
  @Override
  public void contribute(HttpSecurity http) throws Exception {
    JwtAuthenticationProvider jwtAuthenticationProvider = new JwtAuthenticationProvider(jwtDecoder);
    jwtAuthenticationProvider.setJwtAuthenticationConverter(jwtAuthenticationConverter);
    http.oauth2ResourceServer(
        oauth2 ->
            oauth2.authenticationManagerResolver(
                request -> jwtAuthenticationProvider::authenticate));

    http.addFilterAfter(customAuthenticationFilter, BearerTokenAuthenticationFilter.class);
    http.addFilterAfter(actionLoggingFilter, CustomAuthenticationFilter.class);
  }
```

Add the imports:

```java
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
```

Update the class Javadoc first sentence to name the third responsibility:

```java
/**
 * Declares what the auth module installs into the application-wide security chain: the endpoints it
 * serves anonymously, the JWT resource-server mechanism that authenticates every other request, and
 * its own request filters — credential-version resolution first, then action logging, both after
 * bearer-token authentication has populated the security context.
 */
```

`JwtDecoder` injects the `@Primary` bean from `JwtConfiguration`, the same one `SecurityConfiguration` resolved by type; `passwordChangeJwtDecoder` is a named bean and is not a candidate.

- [ ] **Step 4: Strip the resource server from the composition root**

In `SecurityConfiguration.java`, delete these two fields:

```java
  private final Converter<org.springframework.security.oauth2.jwt.Jwt, AbstractAuthenticationToken>
      jwtAuthenticationConverter;
  private final JwtDecoder jwtDecoder;
```

Delete these two lines from the top of `filterChain`:

```java
    JwtAuthenticationProvider jwtAuthenticationProvider = new JwtAuthenticationProvider(jwtDecoder);
    jwtAuthenticationProvider.setJwtAuthenticationConverter(jwtAuthenticationConverter);
```

Delete this configurer from the `http` chain, so `.authorizeHttpRequests(...)` is followed directly by `.exceptionHandling(...)`:

```java
        .oauth2ResourceServer(
            oauth2 ->
                oauth2.authenticationManagerResolver(
                    request -> jwtAuthenticationProvider::authenticate))
```

Delete these now-unused imports:

```java
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationProvider;
```

Keep the `BearerTokenAuthenticationFilter` import — the two `addFilterAfter` calls still use it.

- [ ] **Step 5: Run the unit test to verify it passes**

Run: `./mvnw -o test -Dtest=AuthSecurityContributorTest`
Expected: PASS, `Tests run: 1, Failures: 0, Errors: 0`

- [ ] **Step 6: Verify the composition root is module-free**

```bash
grep -rn "file_processing.auth\|file_processing.fileimport" \
  src/main/java/com/vandunxg/file_processing/configuration/
```

Expected: no output. This is the architectural goal of the whole plan — the composition root imports nothing from any business module.

- [ ] **Step 7: Run the full suite**

Run: `./mvnw -o test`
Expected: `Tests run: 154, Failures: 0, Errors: 0` (baseline 153 plus `AuthSecurityContributorTest`).

Run: `./mvnw spotless:apply && ./mvnw verify`
Expected: BUILD SUCCESS with every IT green — `SecurityConfigurationIT` (5 tests), `AuthControllerIT`, `ActuatorSecurityIT`, and the rest.

If Docker is unavailable, run `./mvnw -o test` and report the ITs as unverified per the environment note. Do not describe the refactor as verified without them.

- [ ] **Step 8: Commit**

```bash
git add -A src/main/java src/test/java
git commit -m "refactor(auth): own the JWT resource-server wiring

Building the JwtAuthenticationProvider from auth's JwtDecoder and
converter made the composition root decide how a request is
authenticated. Move it into AuthSecurityContributor; the root keeps the
chain skeleton, the platform rules, and the 401 entry point. Filter
order is unchanged because addFilterAfter resolves positions from the
static FilterOrderRegistration table, not from configurer order.

configuration/ now imports nothing from any business module."
```

---

## Final report

After Task 4, report:

- Whether `./mvnw verify` ran with Docker, or only `./mvnw -o test` did — and if the latter, list `SecurityConfigurationIT`, `AuthControllerIT`, `ActuatorSecurityIT` as unverified.
- The known finding recorded in spec §7, not fixed here: `ActionLoggingFilter`'s skip pattern is `\/api\/certificate\/.well-known\/jwks\.json` while `JwksController` serves `/api/v1/certificate/.well-known`, so the pattern cannot match. It is unreachable today anyway because that path is in `web.ignoring()`.
- That `fileimport` still holds the legacy `adapter/in|out` layout and still imports `configuration.security.AuthenticatedUser` — both intentional, both out of scope.
