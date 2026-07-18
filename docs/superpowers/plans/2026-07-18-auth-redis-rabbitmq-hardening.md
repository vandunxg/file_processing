# Auth Register Infra Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Caffeine-backed register/resend throttle with a cluster-wide Redis sliding-window rate limiter, move email verification tokens from PostgreSQL to Redis-only storage, and move audit-log recording and verification-email sending from direct synchronous port calls to RabbitMQ-published events consumed by an in-process listener.

**Architecture:** Hexagonal — only adapters change. `RegisterThrottlePort` and `EmailVerificationTokenRepositoryPort` keep their exact signatures (new Redis adapters replace Caffeine/JPA ones). Two new outbound ports (`AuditLogEventPublisherPort`, `VerificationEmailEventPublisherPort`) replace direct calls to the existing `AuditLogPort`/`EmailSenderPort` from the three application services; those two original ports stay unchanged but are now invoked by new `@RabbitListener` inbound adapters instead.

**Tech Stack:** Spring Data Redis (`StringRedisTemplate`, Lua `RedisScript`), `com.vandunxg.common:common-amqp` (already a `pom.xml` dependency, activated via `spring.rabbitmq.enabled: true`), Spring AMQP (`spring-rabbit`, transitively present via `common-amqp`), Testcontainers (`rabbitmq` module, generic Redis container), Awaitility for async test assertions.

## Global Constraints

- Package layout is hexagonal per `RULE.md §3`: `domain/` has zero Spring/JPA imports; `application/` depends only on `domain/` + ports; `adapter/` is the only place with `@RestController`/`@Entity`/`@Repository`/`@RabbitListener`.
- Naming per `RULE.md §4`: ports end in `Port`, adapters end in `Adapter`, services end in `Service`.
- Every `throw` that ends a request is preceded by a `log.warn`/`log.error` line (`RULE.md §6.3`); no new `AuthErrorCode` values are introduced by this plan (all existing error codes are reused as-is) so no new i18n keys are needed.
- `@Slf4j(topic = "AUTH-<FEATURE>")` per class; message format `[methodName] description key={} key={}` (`RULE.md §8.2`). Never log the raw opaque verification token or the full verification link containing it.
- Constructor injection only (`@RequiredArgsConstructor`), never `@Autowired` on a field.
- `mvn spotless:apply` before every commit; each task's own build/test command must pass before moving to the next task.
- Reuse `com.vandunxg.common.utils.MapperFactoryUtils.jacksonMapper()` (Jackson 3, already a transitive dependency via `common-amqp`/`spring-boot-jackson`) instead of hand-rolling an `ObjectMapper` — confirmed present on the classpath (`tools.jackson.core:jackson-databind:3.1.4`) via `mvn dependency:tree`.
- No new `@ConfigurationProperties` classes — extend the existing `AuthProperties` record (`app.auth.*` namespace) with nested records, per `RULE.md §11`.
- Do not touch Login/JWT/RBAC admin code — out of scope, not yet built.
- The `auth_email_verification_tokens` Postgres table and its Flyway migration are left on disk, untouched and unused, by explicit decision — do not add a migration to drop it.
- **Environment note:** this session's sandbox has a `docker` CLI that responds to basic commands, but Testcontainers itself cannot obtain a valid Docker environment from it (`Could not find a valid Docker environment` against every provider strategy, confirmed by the controller directly, not just an implementer). Every Testcontainers-based IT test in this plan (Tasks 1, 2, 6) is expected to report this exact failure when run here — this is a pre-existing environment limitation, not a code defect, and it also affects the repo's pre-existing Postgres-Testcontainers-based tests (`MigrationAndSeedIT`, `UserPersistenceAdapterIT`, the original `AuthControllerIT`), so it did not start with this plan. Implementers: write and self-review the IT test carefully (it will not run to green here); report the Docker unavailability plainly as a concern, do not treat it as a task failure. Reviewers: do not demand a test run as evidence for these specific files — verify correctness by reading the test code and the adapter it exercises instead.

---

## Task 1: Redis Sliding-Window Rate Limiter

**Files:**
- Create: `src/main/resources/scripts/sliding-window-rate-limiter.lua`
- Create: `src/main/java/com/vandunxg/file_processing/auth/configuration/AuthRedisConfiguration.java`
- Create: `src/main/java/com/vandunxg/file_processing/auth/adapter/out/cache/RedisRegisterThrottleAdapter.java`
- Delete: `src/main/java/com/vandunxg/file_processing/auth/adapter/out/cache/CaffeineRegisterThrottleAdapter.java`
- Modify: `src/main/java/com/vandunxg/file_processing/auth/configuration/AuthProperties.java`
- Modify: `src/main/resources/application.yaml`
- Modify: `src/test/resources/application-test.yml`
- Modify: `.env.example`
- Modify: `src/test/java/com/vandunxg/file_processing/auth/application/service/RegisterServiceTest.java`
- Modify: `src/test/java/com/vandunxg/file_processing/auth/application/service/ResendVerificationEmailServiceTest.java`
- Create: `src/test/java/com/vandunxg/file_processing/auth/adapter/out/cache/RedisRegisterThrottleAdapterIT.java`

**Interfaces:**
- Consumes: `RegisterThrottlePort.tryConsume(String key, int maxPerHour)` (unchanged, already exists).
- Produces: `AuthProperties.redis().throttle().keyPrefix(): String`, `AuthProperties.redis().throttle().window(): Duration` — later tasks (2, 3, 4) will further extend the `Redis` nested record and must reproduce this exact shape plus their own additions.

- [ ] **Step 1: Write the Lua script**

`src/main/resources/scripts/sliding-window-rate-limiter.lua`:

```lua
-- KEYS[1] = base key (prefix + caller key, without window suffix)
-- ARGV[1] = limit (max requests per window)
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

- [ ] **Step 2: Extend `AuthProperties` with the Redis/Throttle config**

Replace the full contents of `src/main/java/com/vandunxg/file_processing/auth/configuration/AuthProperties.java` with:

```java
package com.vandunxg.file_processing.auth.configuration;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(
    Password password, Register register, EmailVerification emailVerification, Redis redis) {

  public record Password(String encoder, int bcryptCost, int minLength, int maxLength) {}

  public record Register(int maxAttemptsPerHour) {}

  public record EmailVerification(
      Duration tokenTtl, String baseUrl, int resendMaxAttemptsPerHour) {}

  public record Redis(Throttle throttle) {

    public record Throttle(String keyPrefix, Duration window) {}
  }
}
```

- [ ] **Step 3: Add config to `application.yaml`**

Under the existing `app.auth:` block in `src/main/resources/application.yaml`, immediately after the `email-verification:` block, add:

```yaml
    redis:
      throttle:
        key-prefix: ${AUTH_THROTTLE_REDIS_PREFIX:auth:throttle:}
        window: ${AUTH_THROTTLE_REDIS_WINDOW:PT1H}
```

- [ ] **Step 4: Add the same block to `src/test/resources/application-test.yml`**

Under the existing `app.auth:` block, after `email-verification:`, add:

```yaml
    redis:
      throttle:
        key-prefix: "test:auth:throttle:"
        window: PT1H
```

- [ ] **Step 5: Add env vars to `.env.example`**

After the existing `# Register feature` block, add:

```
AUTH_THROTTLE_REDIS_PREFIX=auth:throttle:
AUTH_THROTTLE_REDIS_WINDOW=PT1H
```

- [ ] **Step 6: Write the Redis script bean configuration**

`src/main/java/com/vandunxg/file_processing/auth/configuration/AuthRedisConfiguration.java`:

```java
package com.vandunxg.file_processing.auth.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

@Configuration
public class AuthRedisConfiguration {

  @Bean
  RedisScript<Long> slidingWindowRateLimiterScript() {
    DefaultRedisScript<Long> script = new DefaultRedisScript<>();
    script.setLocation(new ClassPathResource("scripts/sliding-window-rate-limiter.lua"));
    script.setResultType(Long.class);
    return script;
  }
}
```

(`DefaultRedisScript` has no `(Resource, Class)` constructor in this Spring Data Redis version — confirmed via `javap` against the resolved `spring-data-redis-4.1.0.jar`. Use the no-arg constructor plus `setLocation(Resource)`/`setResultType(Class)` instead; do not declare `throws IOException` on the `@Bean` method — Spring resolves the resource lazily through `DefaultRedisScript`'s own `InitializingBean`/`ScriptSource` machinery.)

- [ ] **Step 7: Write the adapter**

`src/main/java/com/vandunxg/file_processing/auth/adapter/out/cache/RedisRegisterThrottleAdapter.java`:

```java
package com.vandunxg.file_processing.auth.adapter.out.cache;

import java.util.List;

import com.vandunxg.file_processing.auth.application.port.out.RegisterThrottlePort;
import com.vandunxg.file_processing.auth.configuration.AuthProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Cluster-wide, atomic sliding-window-counter rate limiter. Replaces {@code
 * CaffeineRegisterThrottleAdapter}, which was per-instance only.
 */
@Component
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-THROTTLE-REDIS")
public class RedisRegisterThrottleAdapter implements RegisterThrottlePort {

  private final StringRedisTemplate stringRedisTemplate;
  private final RedisScript<Long> slidingWindowRateLimiterScript;
  private final AuthProperties authProperties;

  @Override
  public boolean tryConsume(String key, int maxPerHour) {
    String redisKey = authProperties.redis().throttle().keyPrefix() + key;
    long windowSeconds = authProperties.redis().throttle().window().getSeconds();

    Long allowed =
        stringRedisTemplate.execute(
            slidingWindowRateLimiterScript,
            List.of(redisKey),
            String.valueOf(maxPerHour),
            String.valueOf(windowSeconds));

    boolean result = allowed != null && allowed == 1L;
    log.debug("[tryConsume] evaluated rate limit key={} maxPerHour={} allowed={}", key, maxPerHour, result);
    return result;
  }
}
```

- [ ] **Step 8: Delete the Caffeine adapter**

```bash
rm src/main/java/com/vandunxg/file_processing/auth/adapter/out/cache/CaffeineRegisterThrottleAdapter.java
```

- [ ] **Step 9: Update `RegisterServiceTest` to construct the extended `AuthProperties`**

In `src/test/java/com/vandunxg/file_processing/auth/application/service/RegisterServiceTest.java`, replace the `AuthProperties` construction inside `setUp()`:

```java
    AuthProperties authProperties =
        new AuthProperties(
            new AuthProperties.Password("bcrypt", 10, 8, 128),
            new AuthProperties.Register(5),
            new AuthProperties.EmailVerification(
                Duration.ofMinutes(15), "https://app.example.com/verify", 5),
            new AuthProperties.Redis(new AuthProperties.Redis.Throttle("test:throttle:", Duration.ofHours(1))));
```

- [ ] **Step 10: Update `ResendVerificationEmailServiceTest` the same way**

In `src/test/java/com/vandunxg/file_processing/auth/application/service/ResendVerificationEmailServiceTest.java`, replace the `AuthProperties` construction inside `setUp()` with the identical block shown in Step 9.

- [ ] **Step 11: Run compile + existing unit tests**

```bash
./mvnw -DskipTests clean compile
./mvnw -Dtest=RegisterServiceTest,ResendVerificationEmailServiceTest test
```

Expected: BUILD SUCCESS, all tests pass (these two files only needed the `AuthProperties` construction updated — no assertions changed yet).

- [ ] **Step 12: Write the Redis adapter integration test**

`src/test/java/com/vandunxg/file_processing/auth/adapter/out/cache/RedisRegisterThrottleAdapterIT.java` — hand-wired (no Spring context) against a Testcontainers Redis, so it does not require Postgres to boot:

```java
package com.vandunxg.file_processing.auth.adapter.out.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import com.vandunxg.file_processing.auth.configuration.AuthProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

class RedisRegisterThrottleAdapterIT {

  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
          .withExposedPorts(6379)
          .withReuse(true);

  private static LettuceConnectionFactory connectionFactory;
  private static StringRedisTemplate stringRedisTemplate;
  private static RedisScript<Long> script;

  @BeforeAll
  static void startRedisAndTemplate() {
    REDIS.start();
    connectionFactory =
        new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
    connectionFactory.afterPropertiesSet();
    stringRedisTemplate = new StringRedisTemplate(connectionFactory);
    stringRedisTemplate.afterPropertiesSet();
    DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>();
    redisScript.setLocation(new ClassPathResource("scripts/sliding-window-rate-limiter.lua"));
    redisScript.setResultType(Long.class);
    script = redisScript;
  }

  @AfterAll
  static void stopConnectionFactory() {
    connectionFactory.destroy();
  }

  @Test
  void tryConsume_allowsUpToLimit_thenDeniesFurtherRequestsWithinTheSameWindow() {
    AuthProperties.Redis.Throttle throttleProperties =
        new AuthProperties.Redis.Throttle("it:allow-deny:", Duration.ofHours(1));
    RedisRegisterThrottleAdapter adapter =
        new RedisRegisterThrottleAdapter(
            stringRedisTemplate,
            script,
            new AuthProperties(null, null, null, new AuthProperties.Redis(throttleProperties)));

    String key = "ip-" + System.nanoTime();

    assertThat(adapter.tryConsume(key, 3)).isTrue();
    assertThat(adapter.tryConsume(key, 3)).isTrue();
    assertThat(adapter.tryConsume(key, 3)).isTrue();
    assertThat(adapter.tryConsume(key, 3)).isFalse();
  }

  @Test
  void tryConsume_isolatesDifferentKeys() {
    AuthProperties.Redis.Throttle throttleProperties =
        new AuthProperties.Redis.Throttle("it:isolate:", Duration.ofHours(1));
    RedisRegisterThrottleAdapter adapter =
        new RedisRegisterThrottleAdapter(
            stringRedisTemplate,
            script,
            new AuthProperties(null, null, null, new AuthProperties.Redis(throttleProperties)));

    String keyA = "ip-a-" + System.nanoTime();
    String keyB = "ip-b-" + System.nanoTime();

    assertThat(adapter.tryConsume(keyA, 1)).isTrue();
    assertThat(adapter.tryConsume(keyA, 1)).isFalse();
    assertThat(adapter.tryConsume(keyB, 1)).isTrue();
  }

  @Test
  void tryConsume_allowsAgain_afterTheWindowFullyDecays() throws InterruptedException {
    AuthProperties.Redis.Throttle throttleProperties =
        new AuthProperties.Redis.Throttle("it:decay:", Duration.ofSeconds(2));
    RedisRegisterThrottleAdapter adapter =
        new RedisRegisterThrottleAdapter(
            stringRedisTemplate,
            script,
            new AuthProperties(null, null, null, new AuthProperties.Redis(throttleProperties)));

    String key = "ip-decay-" + System.nanoTime();

    assertThat(adapter.tryConsume(key, 1)).isTrue();
    assertThat(adapter.tryConsume(key, 1)).isFalse();

    // Wait past two full 2s windows so the previous window's weighted contribution decays to
    // (near) zero — a real sleep is unavoidable here since the algorithm reads Redis server TIME.
    Thread.sleep(4500);

    assertThat(adapter.tryConsume(key, 1)).isTrue();
  }
}
```

- [ ] **Step 13: Run the new integration test**

```bash
./mvnw -Dtest=RedisRegisterThrottleAdapterIT test
```

Expected: BUILD SUCCESS (requires Docker available for Testcontainers).

- [ ] **Step 14: Commit**

```bash
git add src/main/resources/scripts/sliding-window-rate-limiter.lua \
        src/main/java/com/vandunxg/file_processing/auth/configuration/AuthRedisConfiguration.java \
        src/main/java/com/vandunxg/file_processing/auth/adapter/out/cache/RedisRegisterThrottleAdapter.java \
        src/main/java/com/vandunxg/file_processing/auth/configuration/AuthProperties.java \
        src/main/resources/application.yaml \
        src/test/resources/application-test.yml \
        .env.example \
        src/test/java/com/vandunxg/file_processing/auth/application/service/RegisterServiceTest.java \
        src/test/java/com/vandunxg/file_processing/auth/application/service/ResendVerificationEmailServiceTest.java \
        src/test/java/com/vandunxg/file_processing/auth/adapter/out/cache/RedisRegisterThrottleAdapterIT.java
git rm src/main/java/com/vandunxg/file_processing/auth/adapter/out/cache/CaffeineRegisterThrottleAdapter.java
git commit -m "$(cat <<'EOF'
feat(auth): replace Caffeine register throttle with Redis sliding-window limiter

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Redis-only Email Verification Token Storage

**Files:**
- Create: `src/main/resources/scripts/email-verification-issue.lua`
- Create: `src/main/resources/scripts/email-verification-invalidate.lua`
- Create: `src/main/java/com/vandunxg/file_processing/auth/adapter/out/cache/EmailVerificationTokenRedisPayload.java`
- Create: `src/main/java/com/vandunxg/file_processing/auth/adapter/out/cache/RedisEmailVerificationTokenAdapter.java`
- Modify: `src/main/java/com/vandunxg/file_processing/auth/configuration/AuthRedisConfiguration.java`
- Modify: `src/main/java/com/vandunxg/file_processing/auth/configuration/AuthProperties.java`
- Modify: `src/main/resources/application.yaml`
- Modify: `src/test/resources/application-test.yml`
- Modify: `.env.example`
- Modify: `src/test/java/com/vandunxg/file_processing/auth/application/service/RegisterServiceTest.java`
- Modify: `src/test/java/com/vandunxg/file_processing/auth/application/service/ResendVerificationEmailServiceTest.java`
- Delete: `src/main/java/com/vandunxg/file_processing/auth/adapter/out/persistence/EmailVerificationTokenPersistenceAdapter.java`
- Delete: `src/main/java/com/vandunxg/file_processing/auth/adapter/out/persistence/entity/JpaEmailVerificationTokenRepository.java`
- Delete: `src/main/java/com/vandunxg/file_processing/auth/adapter/out/persistence/entity/EmailVerificationTokenEntity.java`
- Delete: `src/main/java/com/vandunxg/file_processing/auth/adapter/out/persistence/mapper/EmailVerificationTokenPersistenceMapper.java`
- Delete: `src/test/java/com/vandunxg/file_processing/auth/adapter/out/persistence/EmailVerificationTokenPersistenceAdapterIT.java`
- Create: `src/test/java/com/vandunxg/file_processing/auth/adapter/out/cache/RedisEmailVerificationTokenAdapterIT.java`

**Interfaces:**
- Consumes: `EmailVerificationTokenRepositoryPort` (unchanged signature: `save`, `findByTokenHashForUpdate`, `invalidateAllForUser`); `EmailVerificationToken` domain class (unchanged: `getId/getUserId/getTokenHash/getIssuedAt/getExpiresAt/getUsedAt/getIpAddressHash`, `EmailVerificationToken.builder()...build()`).
- Produces: `AuthProperties.redis().emailVerification().tokenKeyPrefix(): String`, `.userKeyPrefix(): String` — Task 3/4 must reproduce the full `AuthProperties` shape including this addition plus their own.

- [ ] **Step 1: Write the issue Lua script**

`src/main/resources/scripts/email-verification-issue.lua`:

```lua
-- KEYS[1] = token key (token-key-prefix + tokenHash)
-- KEYS[2] = user pointer key (user-key-prefix + userId)
-- ARGV[1] = JSON payload to store at the token key
-- ARGV[2] = token hash (value to store at the user pointer key)
-- ARGV[3] = ttl in seconds (same TTL for both keys)
redis.call('SETEX', KEYS[1], ARGV[3], ARGV[1])
redis.call('SETEX', KEYS[2], ARGV[3], ARGV[2])
return 1
```

- [ ] **Step 2: Write the invalidate Lua script**

`src/main/resources/scripts/email-verification-invalidate.lua`:

```lua
-- KEYS[1] = user pointer key (user-key-prefix + userId)
-- ARGV[1] = token key prefix (to reconstruct the old token's key)
local oldHash = redis.call('GET', KEYS[1])
if oldHash then
  redis.call('DEL', ARGV[1] .. oldHash)
  redis.call('DEL', KEYS[1])
end
return 1
```

- [ ] **Step 3: Extend `AuthProperties` with the email-verification Redis keys**

Replace the full contents of `src/main/java/com/vandunxg/file_processing/auth/configuration/AuthProperties.java` with:

```java
package com.vandunxg.file_processing.auth.configuration;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(
    Password password, Register register, EmailVerification emailVerification, Redis redis) {

  public record Password(String encoder, int bcryptCost, int minLength, int maxLength) {}

  public record Register(int maxAttemptsPerHour) {}

  public record EmailVerification(
      Duration tokenTtl, String baseUrl, int resendMaxAttemptsPerHour) {}

  public record Redis(Throttle throttle, EmailVerificationKeys emailVerification) {

    public record Throttle(String keyPrefix, Duration window) {}

    public record EmailVerificationKeys(String tokenKeyPrefix, String userKeyPrefix) {}
  }
}
```

- [ ] **Step 4: Add config to `application.yaml`**

In `src/main/resources/application.yaml`, under `app.auth.redis:` (added in Task 1), after `throttle:`, add a sibling key:

```yaml
      email-verification:
        token-key-prefix: ${AUTH_EMAIL_VERIFY_TOKEN_PREFIX:auth:email-verify:token:}
        user-key-prefix: ${AUTH_EMAIL_VERIFY_USER_PREFIX:auth:email-verify:user:}
```

So the full `app.auth.redis:` block now reads:

```yaml
    redis:
      throttle:
        key-prefix: ${AUTH_THROTTLE_REDIS_PREFIX:auth:throttle:}
        window: ${AUTH_THROTTLE_REDIS_WINDOW:PT1H}
      email-verification:
        token-key-prefix: ${AUTH_EMAIL_VERIFY_TOKEN_PREFIX:auth:email-verify:token:}
        user-key-prefix: ${AUTH_EMAIL_VERIFY_USER_PREFIX:auth:email-verify:user:}
```

- [ ] **Step 5: Add the same block to `src/test/resources/application-test.yml`**

The full `app.auth.redis:` block becomes:

```yaml
    redis:
      throttle:
        key-prefix: "test:auth:throttle:"
        window: PT1H
      email-verification:
        token-key-prefix: "test:auth:email-verify:token:"
        user-key-prefix: "test:auth:email-verify:user:"
```

- [ ] **Step 6: Add env vars to `.env.example`**

After the `AUTH_THROTTLE_REDIS_WINDOW` line added in Task 1, add:

```
AUTH_EMAIL_VERIFY_TOKEN_PREFIX=auth:email-verify:token:
AUTH_EMAIL_VERIFY_USER_PREFIX=auth:email-verify:user:
```

- [ ] **Step 7: Write the payload record**

`src/main/java/com/vandunxg/file_processing/auth/adapter/out/cache/EmailVerificationTokenRedisPayload.java`:

```java
package com.vandunxg.file_processing.auth.adapter.out.cache;

import java.time.Instant;
import java.util.UUID;

record EmailVerificationTokenRedisPayload(
    UUID id, UUID userId, Instant issuedAt, Instant expiresAt, String ipAddressHash) {}
```

- [ ] **Step 8: Add the two new script beans to `AuthRedisConfiguration`**

Replace the full contents of `src/main/java/com/vandunxg/file_processing/auth/configuration/AuthRedisConfiguration.java` with:

```java
package com.vandunxg.file_processing.auth.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

@Configuration
public class AuthRedisConfiguration {

  @Bean
  RedisScript<Long> slidingWindowRateLimiterScript() {
    DefaultRedisScript<Long> script = new DefaultRedisScript<>();
    script.setLocation(new ClassPathResource("scripts/sliding-window-rate-limiter.lua"));
    script.setResultType(Long.class);
    return script;
  }

  @Bean
  RedisScript<Long> emailVerificationIssueScript() {
    DefaultRedisScript<Long> script = new DefaultRedisScript<>();
    script.setLocation(new ClassPathResource("scripts/email-verification-issue.lua"));
    script.setResultType(Long.class);
    return script;
  }

  @Bean
  RedisScript<Long> emailVerificationInvalidateScript() {
    DefaultRedisScript<Long> script = new DefaultRedisScript<>();
    script.setLocation(new ClassPathResource("scripts/email-verification-invalidate.lua"));
    script.setResultType(Long.class);
    return script;
  }
}
```

(`DefaultRedisScript` has no `(Resource, Class)` constructor in this Spring Data Redis version — use the no-arg constructor plus `setLocation(Resource)`/`setResultType(Class)` as shown above, not a two-arg constructor call. Each adapter's constructor parameter name must exactly match the corresponding `@Bean` method name above — `slidingWindowRateLimiterScript`, `emailVerificationIssueScript`, `emailVerificationInvalidateScript` — since three beans share the type `RedisScript<Long>` and Spring disambiguates same-type beans by matching the injection point's name against the bean name.)

- [ ] **Step 9: Write the adapter**

`src/main/java/com/vandunxg/file_processing/auth/adapter/out/cache/RedisEmailVerificationTokenAdapter.java`:

```java
package com.vandunxg.file_processing.auth.adapter.out.cache;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.vandunxg.common.utils.MapperFactoryUtils;
import com.vandunxg.file_processing.auth.application.port.out.EmailVerificationTokenRepositoryPort;
import com.vandunxg.file_processing.auth.configuration.AuthProperties;
import com.vandunxg.file_processing.auth.domain.model.EmailVerificationToken;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

/**
 * Redis-only token store. Replaces {@code EmailVerificationTokenPersistenceAdapter} — no row is
 * ever written to {@code auth_email_verification_tokens} anymore.
 */
@Repository
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-EMAIL-VERIFICATION-TOKEN-REDIS")
public class RedisEmailVerificationTokenAdapter implements EmailVerificationTokenRepositoryPort {

  private static final ObjectMapper OBJECT_MAPPER = MapperFactoryUtils.jacksonMapper();

  private final StringRedisTemplate stringRedisTemplate;
  private final RedisScript<Long> emailVerificationIssueScript;
  private final RedisScript<Long> emailVerificationInvalidateScript;
  private final AuthProperties authProperties;

  @Override
  public EmailVerificationToken save(EmailVerificationToken token) {
    if (token.getUsedAt() != null) {
      // Already atomically removed from Redis by findByTokenHashForUpdate's GETDEL — there is
      // nothing left to persist. This branch only exists because VerifyEmailService's
      // find-then-consume-then-save call pattern is shared with the JPA adapter this replaces,
      // which did need a second write to record usedAt.
      return token;
    }

    String payload =
        OBJECT_MAPPER.writeValueAsString(
            new EmailVerificationTokenRedisPayload(
                token.getId(),
                token.getUserId(),
                token.getIssuedAt(),
                token.getExpiresAt(),
                token.getIpAddressHash()));
    long ttlSeconds = Math.max(1, Duration.between(Instant.now(), token.getExpiresAt()).getSeconds());

    log.debug("[save] issuing email verification token userId={}", token.getUserId());
    stringRedisTemplate.execute(
        emailVerificationIssueScript,
        List.of(tokenKey(token.getTokenHash()), userKey(token.getUserId())),
        payload,
        token.getTokenHash(),
        String.valueOf(ttlSeconds));
    return token;
  }

  @Override
  public Optional<EmailVerificationToken> findByTokenHashForUpdate(String tokenHash) {
    String json = stringRedisTemplate.opsForValue().getAndDelete(tokenKey(tokenHash));
    if (json == null) {
      return Optional.empty();
    }

    EmailVerificationTokenRedisPayload payload =
        OBJECT_MAPPER.readValue(json, EmailVerificationTokenRedisPayload.class);
    return Optional.of(
        EmailVerificationToken.builder()
            .id(payload.id())
            .userId(payload.userId())
            .tokenHash(tokenHash)
            .issuedAt(payload.issuedAt())
            .expiresAt(payload.expiresAt())
            .ipAddressHash(payload.ipAddressHash())
            .build());
  }

  @Override
  public void invalidateAllForUser(UUID userId, Instant now) {
    stringRedisTemplate.execute(
        emailVerificationInvalidateScript,
        List.of(userKey(userId)),
        authProperties.redis().emailVerification().tokenKeyPrefix());
    log.debug("[invalidateAllForUser] invalidated prior token userId={}", userId);
  }

  private String tokenKey(String tokenHash) {
    return authProperties.redis().emailVerification().tokenKeyPrefix() + tokenHash;
  }

  private String userKey(UUID userId) {
    return authProperties.redis().emailVerification().userKeyPrefix() + userId;
  }
}
```

- [ ] **Step 10: Delete the JPA-based adapter, entity, repository, mapper**

```bash
rm src/main/java/com/vandunxg/file_processing/auth/adapter/out/persistence/EmailVerificationTokenPersistenceAdapter.java
rm src/main/java/com/vandunxg/file_processing/auth/adapter/out/persistence/entity/JpaEmailVerificationTokenRepository.java
rm src/main/java/com/vandunxg/file_processing/auth/adapter/out/persistence/entity/EmailVerificationTokenEntity.java
rm src/main/java/com/vandunxg/file_processing/auth/adapter/out/persistence/mapper/EmailVerificationTokenPersistenceMapper.java
rm src/test/java/com/vandunxg/file_processing/auth/adapter/out/persistence/EmailVerificationTokenPersistenceAdapterIT.java
```

- [ ] **Step 11: Update `RegisterServiceTest`'s `AuthProperties` construction**

In `src/test/java/com/vandunxg/file_processing/auth/application/service/RegisterServiceTest.java`, replace the `AuthProperties` construction inside `setUp()` (built in Task 1 Step 9) with:

```java
    AuthProperties authProperties =
        new AuthProperties(
            new AuthProperties.Password("bcrypt", 10, 8, 128),
            new AuthProperties.Register(5),
            new AuthProperties.EmailVerification(
                Duration.ofMinutes(15), "https://app.example.com/verify", 5),
            new AuthProperties.Redis(
                new AuthProperties.Redis.Throttle("test:throttle:", Duration.ofHours(1)),
                new AuthProperties.Redis.EmailVerificationKeys(
                    "test:email-verify:token:", "test:email-verify:user:")));
```

- [ ] **Step 12: Update `ResendVerificationEmailServiceTest`'s `AuthProperties` construction the same way**

In `src/test/java/com/vandunxg/file_processing/auth/application/service/ResendVerificationEmailServiceTest.java`, replace the `AuthProperties` construction inside `setUp()` with the identical block from Step 11.

- [ ] **Step 13: Run compile + existing unit tests**

```bash
./mvnw -DskipTests clean compile
./mvnw -Dtest=RegisterServiceTest,ResendVerificationEmailServiceTest test
```

Expected: BUILD SUCCESS.

- [ ] **Step 14: Write the Redis adapter integration test**

`src/test/java/com/vandunxg/file_processing/auth/adapter/out/cache/RedisEmailVerificationTokenAdapterIT.java`:

```java
package com.vandunxg.file_processing.auth.adapter.out.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.vandunxg.file_processing.auth.configuration.AuthProperties;
import com.vandunxg.file_processing.auth.domain.model.EmailVerificationToken;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

class RedisEmailVerificationTokenAdapterIT {

  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
          .withExposedPorts(6379)
          .withReuse(true);

  private static LettuceConnectionFactory connectionFactory;
  private static StringRedisTemplate stringRedisTemplate;
  private static RedisScript<Long> issueScript;
  private static RedisScript<Long> invalidateScript;
  private static RedisEmailVerificationTokenAdapter adapter;

  @BeforeAll
  static void startRedisAndAdapter() {
    REDIS.start();
    connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
    connectionFactory.afterPropertiesSet();
    stringRedisTemplate = new StringRedisTemplate(connectionFactory);
    stringRedisTemplate.afterPropertiesSet();
    DefaultRedisScript<Long> issueRedisScript = new DefaultRedisScript<>();
    issueRedisScript.setLocation(new ClassPathResource("scripts/email-verification-issue.lua"));
    issueRedisScript.setResultType(Long.class);
    issueScript = issueRedisScript;
    DefaultRedisScript<Long> invalidateRedisScript = new DefaultRedisScript<>();
    invalidateRedisScript.setLocation(
        new ClassPathResource("scripts/email-verification-invalidate.lua"));
    invalidateRedisScript.setResultType(Long.class);
    invalidateScript = invalidateRedisScript;

    AuthProperties authProperties =
        new AuthProperties(
            null,
            null,
            null,
            new AuthProperties.Redis(
                null,
                new AuthProperties.Redis.EmailVerificationKeys(
                    "it:email-verify:token:", "it:email-verify:user:")));
    adapter =
        new RedisEmailVerificationTokenAdapter(
            stringRedisTemplate, issueScript, invalidateScript, authProperties);
  }

  @AfterAll
  static void stopConnectionFactory() {
    connectionFactory.destroy();
  }

  @Test
  void save_thenFindByTokenHashForUpdate_roundTripsAndDeletesOnRead() {
    UUID userId = UUID.randomUUID();
    String tokenHash = "hash-" + System.nanoTime();
    Instant now = Instant.now();
    EmailVerificationToken token =
        EmailVerificationToken.issue(
            UUID.randomUUID(), userId, tokenHash, now, Duration.ofMinutes(30), "ip-hash-value");

    adapter.save(token);

    EmailVerificationToken found = adapter.findByTokenHashForUpdate(tokenHash).orElseThrow();
    assertThat(found.getId()).isEqualTo(token.getId());
    assertThat(found.getUserId()).isEqualTo(userId);
    assertThat(found.getIpAddressHash()).isEqualTo("ip-hash-value");

    // Second read must be empty — GETDEL already removed the key.
    assertThat(adapter.findByTokenHashForUpdate(tokenHash)).isEmpty();
  }

  @Test
  void findByTokenHashForUpdate_returnsEmpty_whenHashUnknown() {
    assertThat(adapter.findByTokenHashForUpdate("unknown-hash-" + System.nanoTime())).isEmpty();
  }

  @Test
  void invalidateAllForUser_removesTheCurrentToken() {
    UUID userId = UUID.randomUUID();
    String tokenHash = "hash-invalidate-" + System.nanoTime();
    Instant now = Instant.now();
    EmailVerificationToken token =
        EmailVerificationToken.issue(
            UUID.randomUUID(), userId, tokenHash, now, Duration.ofMinutes(30), null);
    adapter.save(token);

    adapter.invalidateAllForUser(userId, now);

    assertThat(adapter.findByTokenHashForUpdate(tokenHash)).isEmpty();
  }

  @Test
  void invalidateAllForUser_isNoOp_whenNoTokenExistsForUser() {
    UUID userId = UUID.randomUUID();

    adapter.invalidateAllForUser(userId, Instant.now());
    // No exception, nothing to assert beyond "did not throw".
  }
}
```

- [ ] **Step 15: Run the new integration test**

```bash
./mvnw -Dtest=RedisEmailVerificationTokenAdapterIT test
```

Expected: BUILD SUCCESS (requires Docker).

- [ ] **Step 16: Run full compile to confirm the deleted JPA classes leave no dangling references**

```bash
./mvnw -DskipTests clean compile
./mvnw -Dtest='MigrationAndSeedIT' test
```

Expected: BUILD SUCCESS — `MigrationAndSeedIT` only uses raw JDBC against `auth_email_verification_tokens`, not the deleted Java classes, so it is unaffected.

- [ ] **Step 17: Commit**

```bash
git add src/main/resources/scripts/email-verification-issue.lua \
        src/main/resources/scripts/email-verification-invalidate.lua \
        src/main/java/com/vandunxg/file_processing/auth/adapter/out/cache/EmailVerificationTokenRedisPayload.java \
        src/main/java/com/vandunxg/file_processing/auth/adapter/out/cache/RedisEmailVerificationTokenAdapter.java \
        src/main/java/com/vandunxg/file_processing/auth/configuration/AuthRedisConfiguration.java \
        src/main/java/com/vandunxg/file_processing/auth/configuration/AuthProperties.java \
        src/main/resources/application.yaml \
        src/test/resources/application-test.yml \
        .env.example \
        src/test/java/com/vandunxg/file_processing/auth/application/service/RegisterServiceTest.java \
        src/test/java/com/vandunxg/file_processing/auth/application/service/ResendVerificationEmailServiceTest.java \
        src/test/java/com/vandunxg/file_processing/auth/adapter/out/cache/RedisEmailVerificationTokenAdapterIT.java
git rm src/main/java/com/vandunxg/file_processing/auth/adapter/out/persistence/EmailVerificationTokenPersistenceAdapter.java \
       src/main/java/com/vandunxg/file_processing/auth/adapter/out/persistence/entity/JpaEmailVerificationTokenRepository.java \
       src/main/java/com/vandunxg/file_processing/auth/adapter/out/persistence/entity/EmailVerificationTokenEntity.java \
       src/main/java/com/vandunxg/file_processing/auth/adapter/out/persistence/mapper/EmailVerificationTokenPersistenceMapper.java \
       src/test/java/com/vandunxg/file_processing/auth/adapter/out/persistence/EmailVerificationTokenPersistenceAdapterIT.java
git commit -m "$(cat <<'EOF'
feat(auth): move email verification tokens from Postgres to Redis-only storage

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: RabbitMQ Topology, Domain Event, Publisher Ports & Adapters

**Files:**
- Modify: `src/main/java/com/vandunxg/file_processing/auth/configuration/AuthProperties.java`
- Modify: `src/main/resources/application.yaml`
- Modify: `src/test/resources/application-test.yml`
- Modify: `.env.example`
- Modify: `docker-compose.yml` (already modified, uncommitted — this task's commit picks it up)
- Create: `src/main/java/com/vandunxg/file_processing/auth/domain/event/SendVerificationEmailEvent.java`
- Create: `src/main/java/com/vandunxg/file_processing/auth/application/port/out/AuditLogEventPublisherPort.java`
- Create: `src/main/java/com/vandunxg/file_processing/auth/application/port/out/VerificationEmailEventPublisherPort.java`
- Create: `src/main/java/com/vandunxg/file_processing/auth/adapter/out/amqp/RabbitAuditLogEventPublisherAdapter.java`
- Create: `src/main/java/com/vandunxg/file_processing/auth/adapter/out/amqp/RabbitVerificationEmailEventPublisherAdapter.java`
- Create: `src/main/java/com/vandunxg/file_processing/auth/configuration/AuthAmqpConfiguration.java`
- Create: `src/test/java/com/vandunxg/file_processing/auth/adapter/out/amqp/RabbitAuditLogEventPublisherAdapterTest.java`
- Create: `src/test/java/com/vandunxg/file_processing/auth/adapter/out/amqp/RabbitVerificationEmailEventPublisherAdapterTest.java`

**Interfaces:**
- Consumes: `com.vandunxg.common.amqp.publisher.AmqpEventPublisher.publish(MessageRoute route, T payload): CompletableFuture<Void>`; `com.vandunxg.common.amqp.publisher.MessageRoute.of(String exchange, String routingKey)`; `com.vandunxg.file_processing.auth.domain.model.AuditLog` (unchanged).
- Produces: `AuditLogEventPublisherPort.publish(AuditLog auditLog): void`; `VerificationEmailEventPublisherPort.publish(String toEmail, String displayName, String verificationLink): void`; `AuthProperties.amqp().exchange(): String`, `.routingKey().auditLog()/.verificationEmail(): String`, `.queue().auditLog()/.verificationEmail(): String` — Task 4 consumes these two new ports; Task 5 consumes the queue names.

- [ ] **Step 1: Confirm `common-amqp` is already a dependency**

`common-amqp` is already present in `pom.xml` (added in an earlier commit). No new dependency is needed — only `spring.rabbitmq.enabled: true` plus connection settings activate it.

The listener container factory (Step 9) needs a local-retry-before-dead-letter policy. On this Spring Boot 4.1 / Spring Framework 7 stack, Spring AMQP 4.1's `org.springframework.amqp.rabbit.config.RetryInterceptorBuilder` builds its retry behavior on top of Spring Framework's own `org.springframework.core.retry.RetryPolicy` (bundled directly in `spring-core`, confirmed present via `javap` against the resolved `spring-core-7.0.6.jar` in the local repo) — **not** the older separate `spring-retry` library, so no new `pom.xml` dependency is needed at all for retry.

- [ ] **Step 2: Extend `AuthProperties` with the Amqp config**

Replace the full contents of `src/main/java/com/vandunxg/file_processing/auth/configuration/AuthProperties.java` with:

```java
package com.vandunxg.file_processing.auth.configuration;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(
    Password password,
    Register register,
    EmailVerification emailVerification,
    Redis redis,
    Amqp amqp) {

  public record Password(String encoder, int bcryptCost, int minLength, int maxLength) {}

  public record Register(int maxAttemptsPerHour) {}

  public record EmailVerification(
      Duration tokenTtl, String baseUrl, int resendMaxAttemptsPerHour) {}

  public record Redis(Throttle throttle, EmailVerificationKeys emailVerification) {

    public record Throttle(String keyPrefix, Duration window) {}

    public record EmailVerificationKeys(String tokenKeyPrefix, String userKeyPrefix) {}
  }

  public record Amqp(String exchange, RoutingKey routingKey, Queue queue) {

    public record RoutingKey(String auditLog, String verificationEmail) {}

    public record Queue(String auditLog, String verificationEmail) {}
  }
}
```

- [ ] **Step 3: Add config to `application.yaml`**

Under the existing `app.auth:` block, after the `redis:` block (from Tasks 1-2), add a sibling key:

```yaml
    amqp:
      exchange: ${AUTH_AMQP_EXCHANGE:auth.events}
      routing-key:
        audit-log: ${AUTH_AMQP_ROUTING_AUDIT_LOG:auth.audit-log.recorded}
        verification-email: ${AUTH_AMQP_ROUTING_VERIFY_EMAIL:auth.email.verification-requested}
      queue:
        audit-log: ${AUTH_AMQP_QUEUE_AUDIT_LOG:auth.audit-log.queue}
        verification-email: ${AUTH_AMQP_QUEUE_VERIFY_EMAIL:auth.email-verification.queue}
```

Also add, at the top level (outside `app:`), a new `spring.rabbitmq` block — if `src/main/resources/application.yaml` does not yet have a `spring.rabbitmq:` key, add it under the existing `spring:` root key, alongside `spring.datasource`/`spring.jpa`/etc.:

```yaml
  rabbitmq:
    enabled: true
    host: ${RABBITMQ_HOST:localhost}
    port: ${RABBITMQ_PORT:5672}
    username: ${RABBITMQ_USERNAME:rabbitmq_user}
    password: ${RABBITMQ_PASSWORD:rabbitmq_password}
```

- [ ] **Step 4: Add the same blocks to `src/test/resources/application-test.yml`**

Under `app.auth:`, after the `redis:` block, add:

```yaml
    amqp:
      exchange: "test.auth.events"
      routing-key:
        audit-log: "test.auth.audit-log.recorded"
        verification-email: "test.auth.email.verification-requested"
      queue:
        audit-log: "test.auth.audit-log.queue"
        verification-email: "test.auth.email-verification.queue"
```

At the top level, add:

```yaml
spring:
  rabbitmq:
    enabled: true
```

(host/port/username/password are supplied per-test-class via `@DynamicPropertySource` in Task 6 — do not hardcode connection settings here.)

- [ ] **Step 5: Add env vars to `.env.example`**

After the `AUTH_EMAIL_VERIFY_USER_PREFIX` line added in Task 2, add:

```
AUTH_AMQP_EXCHANGE=auth.events
AUTH_AMQP_ROUTING_AUDIT_LOG=auth.audit-log.recorded
AUTH_AMQP_ROUTING_VERIFY_EMAIL=auth.email.verification-requested
AUTH_AMQP_QUEUE_AUDIT_LOG=auth.audit-log.queue
AUTH_AMQP_QUEUE_VERIFY_EMAIL=auth.email-verification.queue

# RabbitMQ (docker-compose service "rabbitmq")
RABBITMQ_HOST=localhost
RABBITMQ_PORT=5672
RABBITMQ_USERNAME=rabbitmq_user
RABBITMQ_PASSWORD=rabbitmq_password
```

- [ ] **Step 6: Write the domain event**

`src/main/java/com/vandunxg/file_processing/auth/domain/event/SendVerificationEmailEvent.java`:

```java
package com.vandunxg.file_processing.auth.domain.event;

public record SendVerificationEmailEvent(String toEmail, String displayName, String verificationLink) {}
```

- [ ] **Step 7: Write the two new outbound ports**

`src/main/java/com/vandunxg/file_processing/auth/application/port/out/AuditLogEventPublisherPort.java`:

```java
package com.vandunxg.file_processing.auth.application.port.out;

import com.vandunxg.file_processing.auth.domain.model.AuditLog;

public interface AuditLogEventPublisherPort {

  void publish(AuditLog auditLog);
}
```

`src/main/java/com/vandunxg/file_processing/auth/application/port/out/VerificationEmailEventPublisherPort.java`:

```java
package com.vandunxg.file_processing.auth.application.port.out;

public interface VerificationEmailEventPublisherPort {

  void publish(String toEmail, String displayName, String verificationLink);
}
```

- [ ] **Step 8: Write the two publisher adapters**

`src/main/java/com/vandunxg/file_processing/auth/adapter/out/amqp/RabbitAuditLogEventPublisherAdapter.java`:

```java
package com.vandunxg.file_processing.auth.adapter.out.amqp;

import com.vandunxg.common.amqp.publisher.AmqpEventPublisher;
import com.vandunxg.common.amqp.publisher.MessageRoute;
import com.vandunxg.file_processing.auth.application.port.out.AuditLogEventPublisherPort;
import com.vandunxg.file_processing.auth.configuration.AuthProperties;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-AUDIT-LOG-PUBLISHER")
public class RabbitAuditLogEventPublisherAdapter implements AuditLogEventPublisherPort {

  private final AmqpEventPublisher amqpEventPublisher;
  private final AuthProperties authProperties;

  @Override
  public void publish(AuditLog auditLog) {
    MessageRoute route =
        MessageRoute.of(authProperties.amqp().exchange(), authProperties.amqp().routingKey().auditLog());
    amqpEventPublisher
        .publish(route, auditLog)
        .exceptionally(
            ex -> {
              log.warn(
                  "[publish] failed to publish audit log event objectId={}",
                  auditLog.getObjectId(),
                  ex);
              return null;
            });
  }
}
```

`src/main/java/com/vandunxg/file_processing/auth/adapter/out/amqp/RabbitVerificationEmailEventPublisherAdapter.java`:

```java
package com.vandunxg.file_processing.auth.adapter.out.amqp;

import com.vandunxg.common.amqp.publisher.AmqpEventPublisher;
import com.vandunxg.common.amqp.publisher.MessageRoute;
import com.vandunxg.common.utils.StrUtils;
import com.vandunxg.file_processing.auth.application.port.out.VerificationEmailEventPublisherPort;
import com.vandunxg.file_processing.auth.configuration.AuthProperties;
import com.vandunxg.file_processing.auth.domain.event.SendVerificationEmailEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-EMAIL-EVENT-PUBLISHER")
public class RabbitVerificationEmailEventPublisherAdapter implements VerificationEmailEventPublisherPort {

  private final AmqpEventPublisher amqpEventPublisher;
  private final AuthProperties authProperties;

  @Override
  public void publish(String toEmail, String displayName, String verificationLink) {
    MessageRoute route =
        MessageRoute.of(
            authProperties.amqp().exchange(), authProperties.amqp().routingKey().verificationEmail());
    // Never log verificationLink here: it carries the raw opaque token.
    amqpEventPublisher
        .publish(route, new SendVerificationEmailEvent(toEmail, displayName, verificationLink))
        .exceptionally(
            ex -> {
              log.warn(
                  "[publish] failed to publish verification email event toEmail={}",
                  StrUtils.emailFormat(toEmail),
                  ex);
              return null;
            });
  }
}
```

- [ ] **Step 9: Write the AMQP topology configuration**

`src/main/java/com/vandunxg/file_processing/auth/configuration/AuthAmqpConfiguration.java`:

```java
package com.vandunxg.file_processing.auth.configuration;

import java.time.Duration;

import com.vandunxg.common.amqp.support.QueueOptions;
import lombok.RequiredArgsConstructor;
import org.aopalliance.aop.Advice;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.retry.RejectAndDontRequeueRecoverer;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.retry.RetryPolicy;

@Configuration
@RequiredArgsConstructor
public class AuthAmqpConfiguration {

  private final AuthProperties authProperties;

  @Bean
  TopicExchange authEventsExchange() {
    return new TopicExchange(authProperties.amqp().exchange(), true, false);
  }

  @Bean
  TopicExchange authEventsDeadLetterExchange() {
    return new TopicExchange(authProperties.amqp().exchange() + ".dlx", true, false);
  }

  @Bean
  Queue auditLogQueue() {
    return QueueBuilder.durable(authProperties.amqp().queue().auditLog())
        .withArgument(QueueOptions.DEAD_LETTER_EXCHANGE, authProperties.amqp().exchange() + ".dlx")
        .withArgument(
            QueueOptions.DEAD_LETTER_ROUTING_KEY, authProperties.amqp().routingKey().auditLog())
        .build();
  }

  @Bean
  Queue auditLogDeadLetterQueue() {
    return QueueBuilder.durable(authProperties.amqp().queue().auditLog() + ".dlq").build();
  }

  @Bean
  Queue verificationEmailQueue() {
    return QueueBuilder.durable(authProperties.amqp().queue().verificationEmail())
        .withArgument(QueueOptions.DEAD_LETTER_EXCHANGE, authProperties.amqp().exchange() + ".dlx")
        .withArgument(
            QueueOptions.DEAD_LETTER_ROUTING_KEY,
            authProperties.amqp().routingKey().verificationEmail())
        .build();
  }

  @Bean
  Queue verificationEmailDeadLetterQueue() {
    return QueueBuilder.durable(authProperties.amqp().queue().verificationEmail() + ".dlq").build();
  }

  @Bean
  Binding auditLogBinding() {
    return BindingBuilder.bind(auditLogQueue())
        .to(authEventsExchange())
        .with(authProperties.amqp().routingKey().auditLog());
  }

  @Bean
  Binding auditLogDeadLetterBinding() {
    return BindingBuilder.bind(auditLogDeadLetterQueue())
        .to(authEventsDeadLetterExchange())
        .with(authProperties.amqp().routingKey().auditLog());
  }

  @Bean
  Binding verificationEmailBinding() {
    return BindingBuilder.bind(verificationEmailQueue())
        .to(authEventsExchange())
        .with(authProperties.amqp().routingKey().verificationEmail());
  }

  @Bean
  Binding verificationEmailDeadLetterBinding() {
    return BindingBuilder.bind(verificationEmailDeadLetterQueue())
        .to(authEventsDeadLetterExchange())
        .with(authProperties.amqp().routingKey().verificationEmail());
  }

  @Bean
  SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
      ConnectionFactory connectionFactory, MessageConverter messageConverter) {
    SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
    factory.setConnectionFactory(connectionFactory);
    factory.setMessageConverter(messageConverter);

    // Local retry (3 attempts, 1s -> 10s exponential backoff) runs in-process against the same
    // delivered message, no broker requeue involved. Only once retries are exhausted does
    // RejectAndDontRequeueRecoverer nack without requeue, which is what routes the message to its
    // queue's configured DLX/DLQ (see auditLogQueue()/verificationEmailQueue() above) instead of
    // losing it or retrying forever in a tight loop.
    RetryPolicy retryPolicy =
        RetryPolicy.builder()
            .maxRetries(3)
            .delay(Duration.ofSeconds(1))
            .multiplier(2.0)
            .maxDelay(Duration.ofSeconds(10))
            .build();
    Advice retryAdvice =
        RetryInterceptorBuilder.stateless()
            .retryPolicy(retryPolicy)
            .recoverer(new RejectAndDontRequeueRecoverer())
            .build();
    factory.setContainerCustomizer(container -> container.setAdviceChain(retryAdvice));

    return factory;
  }
}
```

(`RetryInterceptorBuilder`/`RetryPolicy` here are Spring Framework 7's own built-in retry support (`org.springframework.core.retry`, bundled in `spring-core`, already a transitive dependency) plus Spring AMQP 4.1's `RetryInterceptorBuilder` that wraps it — confirmed via `javap` against the actual `spring-rabbit-4.1.0.jar`/`spring-core-7.0.6.jar` in the local repo. This Spring Boot 4.1 stack does **not** need the separate legacy `spring-retry` library for this — skip adding it to `pom.xml`.)

- [ ] **Step 10: Write unit tests for the two publisher adapters**

`src/test/java/com/vandunxg/file_processing/auth/adapter/out/amqp/RabbitAuditLogEventPublisherAdapterTest.java`:

```java
package com.vandunxg.file_processing.auth.adapter.out.amqp;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.vandunxg.common.amqp.publisher.AmqpEventPublisher;
import com.vandunxg.common.amqp.publisher.MessageRoute;
import com.vandunxg.file_processing.auth.configuration.AuthProperties;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import com.vandunxg.file_processing.auth.domain.model.AuditLogDomain;
import com.vandunxg.file_processing.auth.domain.model.OperationType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RabbitAuditLogEventPublisherAdapterTest {

  @Mock private AmqpEventPublisher amqpEventPublisher;

  private final AuthProperties authProperties =
      new AuthProperties(
          null,
          null,
          null,
          null,
          new AuthProperties.Amqp(
              "auth.events",
              new AuthProperties.Amqp.RoutingKey(
                  "auth.audit-log.recorded", "auth.email.verification-requested"),
              new AuthProperties.Amqp.Queue(
                  "auth.audit-log.queue", "auth.email-verification.queue")));

  @Test
  void publish_sendsAuditLogToTheConfiguredRoute() {
    when(amqpEventPublisher.publish(any(MessageRoute.class), any(AuditLog.class)))
        .thenReturn(CompletableFuture.completedFuture(null));
    RabbitAuditLogEventPublisherAdapter adapter =
        new RabbitAuditLogEventPublisherAdapter(amqpEventPublisher, authProperties);
    AuditLog auditLog =
        AuditLog.builder()
            .id(UUID.randomUUID())
            .domain(AuditLogDomain.AUTH)
            .objectId(UUID.randomUUID())
            .operation(OperationType.USER_REGISTERED)
            .changedBy(UUID.randomUUID())
            .changedAt(Instant.now())
            .build();

    adapter.publish(auditLog);

    verify(amqpEventPublisher)
        .publish(eq(MessageRoute.of("auth.events", "auth.audit-log.recorded")), eq(auditLog));
  }

  @Test
  void publish_doesNotThrow_whenAmqpEventPublisherFails() {
    CompletableFuture<Void> failed = new CompletableFuture<>();
    failed.completeExceptionally(new RuntimeException("broker unavailable"));
    when(amqpEventPublisher.publish(any(MessageRoute.class), any(AuditLog.class)))
        .thenReturn(failed);
    RabbitAuditLogEventPublisherAdapter adapter =
        new RabbitAuditLogEventPublisherAdapter(amqpEventPublisher, authProperties);
    AuditLog auditLog =
        AuditLog.builder()
            .id(UUID.randomUUID())
            .domain(AuditLogDomain.AUTH)
            .objectId(UUID.randomUUID())
            .operation(OperationType.USER_REGISTERED)
            .changedBy(UUID.randomUUID())
            .changedAt(Instant.now())
            .build();

    adapter.publish(auditLog);
    // No exception propagates — the failure is only logged via .exceptionally().
  }
}
```

`src/test/java/com/vandunxg/file_processing/auth/adapter/out/amqp/RabbitVerificationEmailEventPublisherAdapterTest.java`:

```java
package com.vandunxg.file_processing.auth.adapter.out.amqp;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.CompletableFuture;

import com.vandunxg.common.amqp.publisher.AmqpEventPublisher;
import com.vandunxg.common.amqp.publisher.MessageRoute;
import com.vandunxg.file_processing.auth.configuration.AuthProperties;
import com.vandunxg.file_processing.auth.domain.event.SendVerificationEmailEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RabbitVerificationEmailEventPublisherAdapterTest {

  @Mock private AmqpEventPublisher amqpEventPublisher;

  private final AuthProperties authProperties =
      new AuthProperties(
          null,
          null,
          null,
          null,
          new AuthProperties.Amqp(
              "auth.events",
              new AuthProperties.Amqp.RoutingKey(
                  "auth.audit-log.recorded", "auth.email.verification-requested"),
              new AuthProperties.Amqp.Queue(
                  "auth.audit-log.queue", "auth.email-verification.queue")));

  @Test
  void publish_sendsEventToTheConfiguredRoute() {
    when(amqpEventPublisher.publish(any(MessageRoute.class), any(SendVerificationEmailEvent.class)))
        .thenReturn(CompletableFuture.completedFuture(null));
    RabbitVerificationEmailEventPublisherAdapter adapter =
        new RabbitVerificationEmailEventPublisherAdapter(amqpEventPublisher, authProperties);

    adapter.publish(
        "operator1@example.com", "Operator One", "https://app.example.com/verify?token=raw");

    verify(amqpEventPublisher)
        .publish(
            eq(MessageRoute.of("auth.events", "auth.email.verification-requested")),
            eq(
                new SendVerificationEmailEvent(
                    "operator1@example.com",
                    "Operator One",
                    "https://app.example.com/verify?token=raw")));
  }

  @Test
  void publish_doesNotThrow_whenAmqpEventPublisherFails() {
    CompletableFuture<Void> failed = new CompletableFuture<>();
    failed.completeExceptionally(new RuntimeException("broker unavailable"));
    when(amqpEventPublisher.publish(any(MessageRoute.class), any(SendVerificationEmailEvent.class)))
        .thenReturn(failed);
    RabbitVerificationEmailEventPublisherAdapter adapter =
        new RabbitVerificationEmailEventPublisherAdapter(amqpEventPublisher, authProperties);

    adapter.publish(
        "operator1@example.com", "Operator One", "https://app.example.com/verify?token=raw");
    // No exception propagates.
  }
}
```

- [ ] **Step 11: Compile and run the new publisher adapter tests**

```bash
./mvnw -DskipTests clean compile
./mvnw -Dtest=RabbitAuditLogEventPublisherAdapterTest,RabbitVerificationEmailEventPublisherAdapterTest test
```

Expected: BUILD SUCCESS. (No listeners exist yet, so `spring.rabbitmq.enabled: true` combined with no `@RabbitListener` beans is safe — the app can start without a reachable broker only failing lazily when a publish is attempted; full end-to-end wiring is verified in Task 6.)

- [ ] **Step 12: Commit**

```bash
git add src/main/java/com/vandunxg/file_processing/auth/configuration/AuthProperties.java \
        src/main/resources/application.yaml \
        src/test/resources/application-test.yml \
        .env.example \
        docker-compose.yml \
        src/main/java/com/vandunxg/file_processing/auth/domain/event/SendVerificationEmailEvent.java \
        src/main/java/com/vandunxg/file_processing/auth/application/port/out/AuditLogEventPublisherPort.java \
        src/main/java/com/vandunxg/file_processing/auth/application/port/out/VerificationEmailEventPublisherPort.java \
        src/main/java/com/vandunxg/file_processing/auth/adapter/out/amqp/RabbitAuditLogEventPublisherAdapter.java \
        src/main/java/com/vandunxg/file_processing/auth/adapter/out/amqp/RabbitVerificationEmailEventPublisherAdapter.java \
        src/main/java/com/vandunxg/file_processing/auth/configuration/AuthAmqpConfiguration.java \
        src/test/java/com/vandunxg/file_processing/auth/adapter/out/amqp/RabbitAuditLogEventPublisherAdapterTest.java \
        src/test/java/com/vandunxg/file_processing/auth/adapter/out/amqp/RabbitVerificationEmailEventPublisherAdapterTest.java
git commit -m "$(cat <<'EOF'
feat(auth): add RabbitMQ topology and audit-log/verification-email event publishers

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Rewire Register/VerifyEmail/ResendVerificationEmail Services to Publish Events

**Files:**
- Modify: `src/main/java/com/vandunxg/file_processing/auth/application/service/RegisterService.java`
- Modify: `src/main/java/com/vandunxg/file_processing/auth/application/service/VerifyEmailService.java`
- Modify: `src/main/java/com/vandunxg/file_processing/auth/application/service/ResendVerificationEmailService.java`
- Modify: `src/test/java/com/vandunxg/file_processing/auth/application/service/RegisterServiceTest.java`
- Modify: `src/test/java/com/vandunxg/file_processing/auth/application/service/VerifyEmailServiceTest.java`
- Modify: `src/test/java/com/vandunxg/file_processing/auth/application/service/ResendVerificationEmailServiceTest.java`

**Interfaces:**
- Consumes: `AuditLogEventPublisherPort.publish(AuditLog)`, `VerificationEmailEventPublisherPort.publish(String, String, String)` (from Task 3).
- Produces: nothing new — `RegisterUseCase`, `VerifyEmailUseCase`, `ResendVerificationEmailUseCase` and their commands/results are all unchanged; only each service's constructor dependencies change (drops `AuditLogPort`/`EmailSenderPort`, gains the two new publisher ports).

- [ ] **Step 1: Rewrite `RegisterService`**

Replace the full contents of `src/main/java/com/vandunxg/file_processing/auth/application/service/RegisterService.java`:

```java
package com.vandunxg.file_processing.auth.application.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import com.vandunxg.common.utils.HashUtils;
import com.vandunxg.common.utils.IdUtils;
import com.vandunxg.common.utils.StrUtils;
import com.vandunxg.file_processing.auth.application.command.RegisterCommand;
import com.vandunxg.file_processing.auth.application.port.in.RegisterUseCase;
import com.vandunxg.file_processing.auth.application.port.out.AuditLogEventPublisherPort;
import com.vandunxg.file_processing.auth.application.port.out.EmailVerificationTokenRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.PasswordHasherPort;
import com.vandunxg.file_processing.auth.application.port.out.RegisterThrottlePort;
import com.vandunxg.file_processing.auth.application.port.out.RoleRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.UserRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.UserRoleRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.VerificationEmailEventPublisherPort;
import com.vandunxg.file_processing.auth.application.port.out.VerificationTokenGeneratorPort;
import com.vandunxg.file_processing.auth.application.result.RegisterResult;
import com.vandunxg.file_processing.auth.configuration.AuthProperties;
import com.vandunxg.file_processing.auth.domain.exception.AuthDomainException;
import com.vandunxg.file_processing.auth.domain.exception.AuthErrorCode;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import com.vandunxg.file_processing.auth.domain.model.AuditLogDomain;
import com.vandunxg.file_processing.auth.domain.model.EmailVerificationToken;
import com.vandunxg.file_processing.auth.domain.model.OperationType;
import com.vandunxg.file_processing.auth.domain.model.Role;
import com.vandunxg.file_processing.auth.domain.model.User;
import com.vandunxg.file_processing.auth.domain.model.UserRole;
import com.vandunxg.file_processing.auth.domain.policy.PasswordPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-REGISTER")
public class RegisterService implements RegisterUseCase {

  private static final String OPERATOR_ROLE_CODE = "OPERATOR";
  private static final String THROTTLE_KEY_PREFIX = "register:";

  private final RegisterThrottlePort throttlePort;
  private final UserRepositoryPort userRepositoryPort;
  private final RoleRepositoryPort roleRepositoryPort;
  private final UserRoleRepositoryPort userRoleRepositoryPort;
  private final AuditLogEventPublisherPort auditLogEventPublisherPort;
  private final EmailVerificationTokenRepositoryPort tokenRepositoryPort;
  private final PasswordHasherPort passwordHasherPort;
  private final VerificationTokenGeneratorPort tokenGeneratorPort;
  private final VerificationEmailEventPublisherPort verificationEmailEventPublisherPort;
  private final AuthProperties authProperties;
  private final Clock clock;

  private final PasswordPolicy passwordPolicy = new PasswordPolicy();

  @Override
  @Transactional
  public RegisterResult register(RegisterCommand command) {
    if (!throttlePort.tryConsume(
        THROTTLE_KEY_PREFIX + command.getIpAddress(),
        authProperties.register().maxAttemptsPerHour())) {
      log.warn(
          "[register] rate limited maxAttemptsPerHour={}",
          authProperties.register().maxAttemptsPerHour());
      throw new AuthDomainException(AuthErrorCode.AUTH_RATE_LIMITED);
    }

    String normalizedUsername = User.normalize(command.getUsername());
    String normalizedEmail = User.normalize(command.getEmail());

    PasswordPolicy.ValidationResult validation =
        passwordPolicy.validate(command.getPassword(), normalizedUsername, normalizedEmail);
    if (!validation.valid()) {
      log.warn(
          "[register] password policy violation username={} reason={}",
          normalizedUsername,
          validation.reason());
      throw new AuthDomainException(AuthErrorCode.PASSWORD_POLICY_VIOLATION);
    }

    if (userRepositoryPort.existsByNormalizedUsername(normalizedUsername)) {
      log.warn("[register] duplicate username username={}", normalizedUsername);
      throw new AuthDomainException(AuthErrorCode.USERNAME_ALREADY_EXISTS);
    }
    if (userRepositoryPort.existsByNormalizedEmail(normalizedEmail)) {
      log.warn("[register] duplicate email email={}", StrUtils.emailFormat(normalizedEmail));
      throw new AuthDomainException(AuthErrorCode.EMAIL_ALREADY_EXISTS);
    }

    Role operatorRole =
        roleRepositoryPort
            .findByCode(OPERATOR_ROLE_CODE)
            .orElseThrow(
                () -> {
                  log.warn("[register] operator role not found code={}", OPERATOR_ROLE_CODE);
                  return new AuthDomainException(AuthErrorCode.INVALID_ROLE);
                });

    Instant now = Instant.now(clock);
    String passwordHash = passwordHasherPort.hash(command.getPassword());
    User user =
        User.register(
            command.getUsername(),
            command.getEmail(),
            command.getDisplayName(),
            passwordHash,
            operatorRole,
            now);

    User saved;
    try {
      saved = userRepositoryPort.save(user);
    } catch (DataIntegrityViolationException e) {
      String cause =
          Optional.ofNullable(e.getMostSpecificCause()).map(Throwable::getMessage).orElse("");
      if (cause.contains("auth_users_normalized_email_uk")) {
        log.warn(
            "[register] concurrent duplicate email detected on save email={}",
            StrUtils.emailFormat(normalizedEmail));
        throw new AuthDomainException(AuthErrorCode.EMAIL_ALREADY_EXISTS);
      }
      log.warn(
          "[register] concurrent duplicate username detected on save username={}",
          normalizedUsername);
      throw new AuthDomainException(AuthErrorCode.USERNAME_ALREADY_EXISTS);
    }

    userRoleRepositoryPort.save(new UserRole(saved.getId(), operatorRole.getId()));

    String rawToken = tokenGeneratorPort.generate();
    String tokenHash = HashUtils.sha256(rawToken.getBytes(StandardCharsets.UTF_8));
    String ipHash =
        command.getIpAddress() == null
            ? null
            : HashUtils.sha256(command.getIpAddress().getBytes(StandardCharsets.UTF_8));

    EmailVerificationToken token =
        EmailVerificationToken.issue(
            IdUtils.nextId(),
            saved.getId(),
            tokenHash,
            now,
            authProperties.emailVerification().tokenTtl(),
            ipHash);
    tokenRepositoryPort.save(token);

    AuditLog auditLog =
        AuditLog.builder()
            .id(IdUtils.nextId())
            .domain(AuditLogDomain.AUTH)
            .objectId(saved.getId())
            .operation(OperationType.USER_REGISTERED)
            .changedBy(saved.getId())
            .changedAt(now)
            .ipAddress(ipHash)
            .build();

    log.info("[register] registered user userId={} status={}", saved.getId(), saved.getStatus());

    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      String verificationLink = authProperties.emailVerification().baseUrl() + "?token=" + rawToken;
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              try {
                auditLogEventPublisherPort.publish(auditLog);
              } catch (Exception e) {
                log.warn(
                    "[register] failed to publish audit log event after commit userId={}",
                    saved.getId(),
                    e);
              }
              try {
                verificationEmailEventPublisherPort.publish(
                    saved.getEmail(), saved.getDisplayName(), verificationLink);
              } catch (Exception e) {
                log.warn(
                    "[register] failed to publish verification email event after commit userId={}",
                    saved.getId(),
                    e);
              }
            }
          });
    }

    return RegisterResult.from(saved);
  }
}
```

- [ ] **Step 2: Rewrite `VerifyEmailService`**

Replace the full contents of `src/main/java/com/vandunxg/file_processing/auth/application/service/VerifyEmailService.java`:

```java
package com.vandunxg.file_processing.auth.application.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;

import com.vandunxg.common.utils.HashUtils;
import com.vandunxg.common.utils.IdUtils;
import com.vandunxg.file_processing.auth.application.command.VerifyEmailCommand;
import com.vandunxg.file_processing.auth.application.port.in.VerifyEmailUseCase;
import com.vandunxg.file_processing.auth.application.port.out.AuditLogEventPublisherPort;
import com.vandunxg.file_processing.auth.application.port.out.EmailVerificationTokenRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.UserRepositoryPort;
import com.vandunxg.file_processing.auth.application.result.RegisterResult;
import com.vandunxg.file_processing.auth.domain.exception.AuthDomainException;
import com.vandunxg.file_processing.auth.domain.exception.AuthErrorCode;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import com.vandunxg.file_processing.auth.domain.model.AuditLogDomain;
import com.vandunxg.file_processing.auth.domain.model.EmailVerificationToken;
import com.vandunxg.file_processing.auth.domain.model.OperationType;
import com.vandunxg.file_processing.auth.domain.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-VERIFY-EMAIL")
public class VerifyEmailService implements VerifyEmailUseCase {

  private final EmailVerificationTokenRepositoryPort tokenRepositoryPort;
  private final UserRepositoryPort userRepositoryPort;
  private final AuditLogEventPublisherPort auditLogEventPublisherPort;
  private final Clock clock;

  @Override
  @Transactional
  public RegisterResult verifyEmail(VerifyEmailCommand command) {
    String tokenHash = HashUtils.sha256(command.getToken().getBytes(StandardCharsets.UTF_8));

    EmailVerificationToken token =
        tokenRepositoryPort
            .findByTokenHashForUpdate(tokenHash)
            .orElseThrow(
                () -> {
                  log.warn("[verifyEmail] unknown token presented");
                  return new AuthDomainException(AuthErrorCode.EMAIL_VERIFICATION_TOKEN_INVALID);
                });

    Instant now = Instant.now(clock);
    try {
      token.consume(now);
    } catch (AuthDomainException e) {
      log.warn("[verifyEmail] token consume rejected tokenId={}", token.getId());
      throw e;
    }
    tokenRepositoryPort.save(token);

    User user =
        userRepositoryPort
            .findById(token.getUserId())
            .orElseThrow(
                () -> {
                  log.warn(
                      "[verifyEmail] user not found for verified token tokenId={} userId={}",
                      token.getId(),
                      token.getUserId());
                  return new AuthDomainException(AuthErrorCode.EMAIL_VERIFICATION_TOKEN_INVALID);
                });

    user.verifyEmail(now);
    User saved = userRepositoryPort.save(user);

    AuditLog auditLog =
        AuditLog.builder()
            .id(IdUtils.nextId())
            .domain(AuditLogDomain.AUTH)
            .objectId(saved.getId())
            .operation(OperationType.EMAIL_VERIFIED)
            .changedBy(saved.getId())
            .changedAt(now)
            .build();

    log.info("[verifyEmail] verified email userId={} status={}", saved.getId(), saved.getStatus());

    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              try {
                auditLogEventPublisherPort.publish(auditLog);
              } catch (Exception e) {
                log.warn(
                    "[verifyEmail] failed to publish audit log event after commit userId={}",
                    saved.getId(),
                    e);
              }
            }
          });
    }

    return RegisterResult.from(saved);
  }
}
```

- [ ] **Step 3: Rewrite `ResendVerificationEmailService`**

Replace the full contents of `src/main/java/com/vandunxg/file_processing/auth/application/service/ResendVerificationEmailService.java`:

```java
package com.vandunxg.file_processing.auth.application.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;

import com.vandunxg.common.utils.HashUtils;
import com.vandunxg.common.utils.IdUtils;
import com.vandunxg.file_processing.auth.application.command.ResendVerificationEmailCommand;
import com.vandunxg.file_processing.auth.application.port.in.ResendVerificationEmailUseCase;
import com.vandunxg.file_processing.auth.application.port.out.AuditLogEventPublisherPort;
import com.vandunxg.file_processing.auth.application.port.out.EmailVerificationTokenRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.RegisterThrottlePort;
import com.vandunxg.file_processing.auth.application.port.out.UserRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.VerificationEmailEventPublisherPort;
import com.vandunxg.file_processing.auth.application.port.out.VerificationTokenGeneratorPort;
import com.vandunxg.file_processing.auth.configuration.AuthProperties;
import com.vandunxg.file_processing.auth.domain.exception.AuthDomainException;
import com.vandunxg.file_processing.auth.domain.exception.AuthErrorCode;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import com.vandunxg.file_processing.auth.domain.model.AuditLogDomain;
import com.vandunxg.file_processing.auth.domain.model.EmailVerificationToken;
import com.vandunxg.file_processing.auth.domain.model.OperationType;
import com.vandunxg.file_processing.auth.domain.model.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-RESEND-VERIFICATION")
public class ResendVerificationEmailService implements ResendVerificationEmailUseCase {

  private static final String THROTTLE_KEY_PREFIX = "resend:";

  private final RegisterThrottlePort throttlePort;
  private final UserRepositoryPort userRepositoryPort;
  private final EmailVerificationTokenRepositoryPort tokenRepositoryPort;
  private final AuditLogEventPublisherPort auditLogEventPublisherPort;
  private final VerificationTokenGeneratorPort tokenGeneratorPort;
  private final VerificationEmailEventPublisherPort verificationEmailEventPublisherPort;
  private final AuthProperties authProperties;
  private final Clock clock;

  @Override
  @Transactional
  public void resend(ResendVerificationEmailCommand command) {
    if (!throttlePort.tryConsume(
        THROTTLE_KEY_PREFIX + command.getIpAddress(),
        authProperties.emailVerification().resendMaxAttemptsPerHour())) {
      log.warn(
          "[resend] rate limited maxAttemptsPerHour={}",
          authProperties.emailVerification().resendMaxAttemptsPerHour());
      throw new AuthDomainException(AuthErrorCode.AUTH_RATE_LIMITED);
    }

    String normalizedIdentifier = User.normalize(command.getIdentifier());
    User user = userRepositoryPort.findByNormalizedIdentifier(normalizedIdentifier).orElse(null);
    if (user == null || !user.isPendingVerify()) {
      log.info("[resend] no-op");
      return;
    }

    Instant now = Instant.now(clock);
    tokenRepositoryPort.invalidateAllForUser(user.getId(), now);

    String rawToken = tokenGeneratorPort.generate();
    String tokenHash = HashUtils.sha256(rawToken.getBytes(StandardCharsets.UTF_8));
    String ipHash =
        command.getIpAddress() == null
            ? null
            : HashUtils.sha256(command.getIpAddress().getBytes(StandardCharsets.UTF_8));

    EmailVerificationToken token =
        EmailVerificationToken.issue(
            IdUtils.nextId(),
            user.getId(),
            tokenHash,
            now,
            authProperties.emailVerification().tokenTtl(),
            ipHash);
    tokenRepositoryPort.save(token);

    AuditLog auditLog =
        AuditLog.builder()
            .id(IdUtils.nextId())
            .domain(AuditLogDomain.AUTH)
            .objectId(user.getId())
            .operation(OperationType.EMAIL_VERIFICATION_REQUESTED)
            .changedBy(user.getId())
            .changedAt(now)
            .ipAddress(ipHash)
            .build();

    log.info("[resend] issued new verification token userId={}", user.getId());

    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      String verificationLink = authProperties.emailVerification().baseUrl() + "?token=" + rawToken;
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              try {
                auditLogEventPublisherPort.publish(auditLog);
              } catch (Exception e) {
                log.warn(
                    "[resend] failed to publish audit log event after commit userId={}",
                    user.getId(),
                    e);
              }
              try {
                verificationEmailEventPublisherPort.publish(
                    user.getEmail(), user.getDisplayName(), verificationLink);
              } catch (Exception e) {
                log.warn(
                    "[resend] failed to publish verification email event after commit userId={}",
                    user.getId(),
                    e);
              }
            }
          });
    }
  }
}
```

- [ ] **Step 4: Rewrite `RegisterServiceTest`**

Replace the full contents of `src/test/java/com/vandunxg/file_processing/auth/application/service/RegisterServiceTest.java`:

```java
package com.vandunxg.file_processing.auth.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

import com.vandunxg.common.utils.HashUtils;
import com.vandunxg.file_processing.auth.application.command.RegisterCommand;
import com.vandunxg.file_processing.auth.application.port.out.AuditLogEventPublisherPort;
import com.vandunxg.file_processing.auth.application.port.out.EmailVerificationTokenRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.PasswordHasherPort;
import com.vandunxg.file_processing.auth.application.port.out.RegisterThrottlePort;
import com.vandunxg.file_processing.auth.application.port.out.RoleRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.UserRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.UserRoleRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.VerificationEmailEventPublisherPort;
import com.vandunxg.file_processing.auth.application.port.out.VerificationTokenGeneratorPort;
import com.vandunxg.file_processing.auth.application.result.RegisterResult;
import com.vandunxg.file_processing.auth.configuration.AuthProperties;
import com.vandunxg.file_processing.auth.domain.exception.AuthDomainException;
import com.vandunxg.file_processing.auth.domain.exception.AuthErrorCode;
import com.vandunxg.file_processing.auth.domain.model.ActiveStatus;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import com.vandunxg.file_processing.auth.domain.model.EmailVerificationToken;
import com.vandunxg.file_processing.auth.domain.model.OperationType;
import com.vandunxg.file_processing.auth.domain.model.Role;
import com.vandunxg.file_processing.auth.domain.model.User;
import com.vandunxg.file_processing.auth.domain.model.UserRole;
import com.vandunxg.file_processing.auth.domain.model.UserStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class RegisterServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-17T10:15:30Z");

  @Mock private RegisterThrottlePort throttlePort;
  @Mock private UserRepositoryPort userRepositoryPort;
  @Mock private RoleRepositoryPort roleRepositoryPort;
  @Mock private UserRoleRepositoryPort userRoleRepositoryPort;
  @Mock private AuditLogEventPublisherPort auditLogEventPublisherPort;
  @Mock private EmailVerificationTokenRepositoryPort tokenRepositoryPort;
  @Mock private PasswordHasherPort passwordHasherPort;
  @Mock private VerificationTokenGeneratorPort tokenGeneratorPort;
  @Mock private VerificationEmailEventPublisherPort verificationEmailEventPublisherPort;

  private RegisterService registerService;

  @BeforeEach
  void setUp() {
    AuthProperties authProperties =
        new AuthProperties(
            new AuthProperties.Password("bcrypt", 10, 8, 128),
            new AuthProperties.Register(5),
            new AuthProperties.EmailVerification(
                Duration.ofMinutes(15), "https://app.example.com/verify", 5),
            new AuthProperties.Redis(
                new AuthProperties.Redis.Throttle("test:throttle:", Duration.ofHours(1)),
                new AuthProperties.Redis.EmailVerificationKeys(
                    "test:email-verify:token:", "test:email-verify:user:")),
            new AuthProperties.Amqp(
                "test.auth.events",
                new AuthProperties.Amqp.RoutingKey("test.audit-log", "test.verification-email"),
                new AuthProperties.Amqp.Queue("test.audit-log.queue", "test.verification-email.queue")));
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    registerService =
        new RegisterService(
            throttlePort,
            userRepositoryPort,
            roleRepositoryPort,
            userRoleRepositoryPort,
            auditLogEventPublisherPort,
            tokenRepositoryPort,
            passwordHasherPort,
            tokenGeneratorPort,
            verificationEmailEventPublisherPort,
            authProperties,
            clock);
  }

  @AfterEach
  void tearDown() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void registerReturnsPendingVerifyResultAndPublishesEventsAfterCommitWhenValid() {
    Role operatorRole = operatorRole();
    RegisterCommand command = validCommand();
    givenThrottleAllows();
    givenNoExistingUsernameOrEmail();
    when(roleRepositoryPort.findByCode("OPERATOR")).thenReturn(Optional.of(operatorRole));
    when(passwordHasherPort.hash(command.getPassword())).thenReturn("{bcrypt}hashed");
    when(userRepositoryPort.save(any(User.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(userRoleRepositoryPort.save(any(UserRole.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(tokenGeneratorPort.generate()).thenReturn("raw-verification-token");
    when(tokenRepositoryPort.save(any(EmailVerificationToken.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    TransactionSynchronizationManager.initSynchronization();

    RegisterResult result = registerService.register(command);

    assertThat(result.getStatus()).isEqualTo(UserStatus.PENDING_VERIFY);
    assertThat(result.getUsername()).isEqualTo("operator1");
    assertThat(result.getEmail()).isEqualTo("operator1@example.com");
    assertThat(result.getDisplayName()).isEqualTo("Operator One");
    assertThat(result.getId()).isNotNull();

    ArgumentCaptor<UserRole> userRoleCaptor = ArgumentCaptor.forClass(UserRole.class);
    verify(userRoleRepositoryPort).save(userRoleCaptor.capture());
    assertThat(userRoleCaptor.getValue().getUserId()).isEqualTo(result.getId());
    assertThat(userRoleCaptor.getValue().getRoleId()).isEqualTo(operatorRole.getId());

    ArgumentCaptor<EmailVerificationToken> tokenCaptor =
        ArgumentCaptor.forClass(EmailVerificationToken.class);
    verify(tokenRepositoryPort).save(tokenCaptor.capture());
    String expectedHash =
        HashUtils.sha256("raw-verification-token".getBytes(StandardCharsets.UTF_8));
    assertThat(tokenCaptor.getValue().getTokenHash()).isEqualTo(expectedHash);
    assertThat(tokenCaptor.getValue().getTokenHash()).isNotEqualTo("raw-verification-token");
    assertThat(tokenCaptor.getValue().getUserId()).isEqualTo(result.getId());

    verifyNoInteractions(auditLogEventPublisherPort, verificationEmailEventPublisherPort);

    new ArrayList<>(TransactionSynchronizationManager.getSynchronizations())
        .forEach(TransactionSynchronization::afterCommit);

    ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogEventPublisherPort).publish(auditCaptor.capture());
    assertThat(auditCaptor.getValue().getOperation()).isEqualTo(OperationType.USER_REGISTERED);
    assertThat(auditCaptor.getValue().getObjectId()).isEqualTo(result.getId());
    assertThat(auditCaptor.getValue().getChangedBy()).isEqualTo(result.getId());

    verify(verificationEmailEventPublisherPort)
        .publish(
            "operator1@example.com",
            "Operator One",
            "https://app.example.com/verify?token=raw-verification-token");
  }

  @Test
  void registerThrowsUsernameAlreadyExistsWhenUsernameAlreadyTaken() {
    RegisterCommand command = validCommand();
    givenThrottleAllows();
    when(userRepositoryPort.existsByNormalizedUsername("operator1")).thenReturn(true);

    assertThatThrownBy(() -> registerService.register(command))
        .isInstanceOf(AuthDomainException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.USERNAME_ALREADY_EXISTS);

    verify(passwordHasherPort, never()).hash(anyString());
    verify(tokenGeneratorPort, never()).generate();
    verify(userRepositoryPort, never()).save(any());
  }

  @Test
  void registerThrowsEmailAlreadyExistsWhenEmailAlreadyTaken() {
    RegisterCommand command = validCommand();
    givenThrottleAllows();
    when(userRepositoryPort.existsByNormalizedUsername("operator1")).thenReturn(false);
    when(userRepositoryPort.existsByNormalizedEmail("operator1@example.com")).thenReturn(true);

    assertThatThrownBy(() -> registerService.register(command))
        .isInstanceOf(AuthDomainException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.EMAIL_ALREADY_EXISTS);

    verify(passwordHasherPort, never()).hash(anyString());
    verify(tokenGeneratorPort, never()).generate();
    verify(userRepositoryPort, never()).save(any());
  }

  @Test
  void registerTranslatesConcurrentSaveFailureToUsernameAlreadyExistsWhenUsernameConstraintFires() {
    RegisterCommand command = validCommand();
    givenThrottleAllows();
    givenNoExistingUsernameOrEmail();
    when(roleRepositoryPort.findByCode("OPERATOR")).thenReturn(Optional.of(operatorRole()));
    when(passwordHasherPort.hash(command.getPassword())).thenReturn("{bcrypt}hashed");
    when(userRepositoryPort.save(any(User.class)))
        .thenThrow(
            new DataIntegrityViolationException(
                "duplicate key value violates unique constraint"
                    + " \"auth_users_normalized_username_uk\""));

    assertThatThrownBy(() -> registerService.register(command))
        .isInstanceOf(AuthDomainException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.USERNAME_ALREADY_EXISTS);

    verifyNoInteractions(
        userRoleRepositoryPort,
        tokenRepositoryPort,
        auditLogEventPublisherPort,
        verificationEmailEventPublisherPort);
  }

  @Test
  void registerTranslatesConcurrentSaveFailureToEmailAlreadyExistsWhenEmailConstraintFires() {
    RegisterCommand command = validCommand();
    givenThrottleAllows();
    givenNoExistingUsernameOrEmail();
    when(roleRepositoryPort.findByCode("OPERATOR")).thenReturn(Optional.of(operatorRole()));
    when(passwordHasherPort.hash(command.getPassword())).thenReturn("{bcrypt}hashed");
    when(userRepositoryPort.save(any(User.class)))
        .thenThrow(
            new DataIntegrityViolationException(
                "duplicate key value violates unique constraint"
                    + " \"auth_users_normalized_email_uk\""));

    assertThatThrownBy(() -> registerService.register(command))
        .isInstanceOf(AuthDomainException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.EMAIL_ALREADY_EXISTS);

    verifyNoInteractions(
        userRoleRepositoryPort,
        tokenRepositoryPort,
        auditLogEventPublisherPort,
        verificationEmailEventPublisherPort);
  }

  @Test
  void registerDefaultsToUsernameAlreadyExistsWhenConstraintNameIsUnparseable() {
    RegisterCommand command = validCommand();
    givenThrottleAllows();
    givenNoExistingUsernameOrEmail();
    when(roleRepositoryPort.findByCode("OPERATOR")).thenReturn(Optional.of(operatorRole()));
    when(passwordHasherPort.hash(command.getPassword())).thenReturn("{bcrypt}hashed");
    when(userRepositoryPort.save(any(User.class)))
        .thenThrow(new DataIntegrityViolationException("connection reset by peer"));

    assertThatThrownBy(() -> registerService.register(command))
        .isInstanceOf(AuthDomainException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.USERNAME_ALREADY_EXISTS);
  }

  @Test
  void registerThrowsPasswordPolicyViolationWhenPasswordTooShortAndNeverHashesOrGeneratesToken() {
    RegisterCommand command =
        RegisterCommand.builder()
            .username("operator1")
            .email("operator1@example.com")
            .displayName("Operator One")
            .password("short1")
            .ipAddress("203.0.113.5")
            .build();
    givenThrottleAllows();

    assertThatThrownBy(() -> registerService.register(command))
        .isInstanceOf(AuthDomainException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.PASSWORD_POLICY_VIOLATION);

    verify(passwordHasherPort, never()).hash(anyString());
    verify(tokenGeneratorPort, never()).generate();
    verifyNoInteractions(
        userRepositoryPort,
        roleRepositoryPort,
        userRoleRepositoryPort,
        tokenRepositoryPort,
        auditLogEventPublisherPort,
        verificationEmailEventPublisherPort);
  }

  @Test
  void registerThrowsRateLimitedBeforeAnyOtherCheckWhenThrottleExceeded() {
    RegisterCommand command = validCommand();
    when(throttlePort.tryConsume("register:" + command.getIpAddress(), 5)).thenReturn(false);

    assertThatThrownBy(() -> registerService.register(command))
        .isInstanceOf(AuthDomainException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.AUTH_RATE_LIMITED);

    verifyNoInteractions(
        roleRepositoryPort,
        userRepositoryPort,
        passwordHasherPort,
        tokenGeneratorPort,
        userRoleRepositoryPort,
        tokenRepositoryPort,
        auditLogEventPublisherPort,
        verificationEmailEventPublisherPort);
  }

  @Test
  void registerThrowsInvalidRoleWhenOperatorRoleNotFound() {
    RegisterCommand command = validCommand();
    givenThrottleAllows();
    givenNoExistingUsernameOrEmail();
    when(roleRepositoryPort.findByCode("OPERATOR")).thenReturn(Optional.empty());

    assertThatThrownBy(() -> registerService.register(command))
        .isInstanceOf(AuthDomainException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.INVALID_ROLE);

    verify(passwordHasherPort, never()).hash(anyString());
    verify(tokenGeneratorPort, never()).generate();
  }

  private void givenThrottleAllows() {
    when(throttlePort.tryConsume(anyString(), anyInt())).thenReturn(true);
  }

  private void givenNoExistingUsernameOrEmail() {
    when(userRepositoryPort.existsByNormalizedUsername(anyString())).thenReturn(false);
    when(userRepositoryPort.existsByNormalizedEmail(anyString())).thenReturn(false);
  }

  private static RegisterCommand validCommand() {
    return RegisterCommand.builder()
        .username("operator1")
        .email("operator1@example.com")
        .displayName("Operator One")
        .password("StrongPassw0rd!")
        .ipAddress("203.0.113.5")
        .build();
  }

  private static Role operatorRole() {
    return Role.builder()
        .id(UUID.randomUUID())
        .code("OPERATOR")
        .status(ActiveStatus.ACTIVE)
        .build();
  }
}
```

- [ ] **Step 5: Rewrite `VerifyEmailServiceTest`**

Replace the full contents of `src/test/java/com/vandunxg/file_processing/auth/application/service/VerifyEmailServiceTest.java`:

```java
package com.vandunxg.file_processing.auth.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

import com.vandunxg.common.utils.HashUtils;
import com.vandunxg.file_processing.auth.application.command.VerifyEmailCommand;
import com.vandunxg.file_processing.auth.application.port.out.AuditLogEventPublisherPort;
import com.vandunxg.file_processing.auth.application.port.out.EmailVerificationTokenRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.UserRepositoryPort;
import com.vandunxg.file_processing.auth.application.result.RegisterResult;
import com.vandunxg.file_processing.auth.domain.exception.AuthDomainException;
import com.vandunxg.file_processing.auth.domain.exception.AuthErrorCode;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import com.vandunxg.file_processing.auth.domain.model.EmailVerificationToken;
import com.vandunxg.file_processing.auth.domain.model.OperationType;
import com.vandunxg.file_processing.auth.domain.model.User;
import com.vandunxg.file_processing.auth.domain.model.UserStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class VerifyEmailServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-17T10:15:30Z");
  private static final String RAW_TOKEN = "raw-verification-token";
  private static final String TOKEN_HASH =
      HashUtils.sha256(RAW_TOKEN.getBytes(StandardCharsets.UTF_8));

  @Mock private EmailVerificationTokenRepositoryPort tokenRepositoryPort;
  @Mock private UserRepositoryPort userRepositoryPort;
  @Mock private AuditLogEventPublisherPort auditLogEventPublisherPort;

  private VerifyEmailService verifyEmailService;

  @BeforeEach
  void setUp() {
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    verifyEmailService =
        new VerifyEmailService(
            tokenRepositoryPort, userRepositoryPort, auditLogEventPublisherPort, clock);
  }

  @AfterEach
  void tearDown() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void verifyEmailActivatesUserConsumesTokenAndPublishesAuditEventAfterCommitWhenTokenValid() {
    UUID userId = UUID.randomUUID();
    EmailVerificationToken token = pendingToken(userId);
    User user = pendingUser(userId);

    when(tokenRepositoryPort.findByTokenHashForUpdate(TOKEN_HASH)).thenReturn(Optional.of(token));
    when(tokenRepositoryPort.save(any(EmailVerificationToken.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));
    when(userRepositoryPort.findById(userId)).thenReturn(Optional.of(user));
    when(userRepositoryPort.save(any(User.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    TransactionSynchronizationManager.initSynchronization();

    RegisterResult result =
        verifyEmailService.verifyEmail(VerifyEmailCommand.builder().token(RAW_TOKEN).build());

    assertThat(result.getId()).isEqualTo(userId);
    assertThat(result.getStatus()).isEqualTo(UserStatus.ACTIVE);
    assertThat(user.getStatus()).isEqualTo(UserStatus.ACTIVE);
    assertThat(token.getUsedAt()).isEqualTo(NOW);

    verifyNoInteractions(auditLogEventPublisherPort);

    new ArrayList<>(TransactionSynchronizationManager.getSynchronizations())
        .forEach(TransactionSynchronization::afterCommit);

    ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogEventPublisherPort).publish(auditCaptor.capture());
    assertThat(auditCaptor.getValue().getOperation()).isEqualTo(OperationType.EMAIL_VERIFIED);
    assertThat(auditCaptor.getValue().getObjectId()).isEqualTo(userId);
    assertThat(auditCaptor.getValue().getChangedBy()).isEqualTo(userId);

    // second call with the same raw token must fail: the mock returns the same
    // (now-consumed) token instance, so EmailVerificationToken#consume rejects it.
    assertThatThrownBy(
            () ->
                verifyEmailService.verifyEmail(
                    VerifyEmailCommand.builder().token(RAW_TOKEN).build()))
        .isInstanceOf(AuthDomainException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.EMAIL_VERIFICATION_TOKEN_INVALID);
  }

  @Test
  void verifyEmailThrowsInvalidTokenWhenTokenHashUnknown() {
    when(tokenRepositoryPort.findByTokenHashForUpdate(TOKEN_HASH)).thenReturn(Optional.empty());

    assertThatThrownBy(
            () ->
                verifyEmailService.verifyEmail(
                    VerifyEmailCommand.builder().token(RAW_TOKEN).build()))
        .isInstanceOf(AuthDomainException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.EMAIL_VERIFICATION_TOKEN_INVALID);

    verify(tokenRepositoryPort, never()).save(any());
    verifyNoInteractions(userRepositoryPort, auditLogEventPublisherPort);
  }

  @Test
  void verifyEmailThrowsInvalidTokenWhenTokenExpired() {
    EmailVerificationToken token = expiredToken(UUID.randomUUID());
    when(tokenRepositoryPort.findByTokenHashForUpdate(TOKEN_HASH)).thenReturn(Optional.of(token));

    assertThatThrownBy(
            () ->
                verifyEmailService.verifyEmail(
                    VerifyEmailCommand.builder().token(RAW_TOKEN).build()))
        .isInstanceOf(AuthDomainException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.EMAIL_VERIFICATION_TOKEN_INVALID);

    verify(tokenRepositoryPort, never()).save(any());
    verifyNoInteractions(userRepositoryPort, auditLogEventPublisherPort);
  }

  @Test
  void verifyEmailThrowsInvalidTokenWhenTokenAlreadyUsed() {
    EmailVerificationToken token = usedToken(UUID.randomUUID());
    when(tokenRepositoryPort.findByTokenHashForUpdate(TOKEN_HASH)).thenReturn(Optional.of(token));

    assertThatThrownBy(
            () ->
                verifyEmailService.verifyEmail(
                    VerifyEmailCommand.builder().token(RAW_TOKEN).build()))
        .isInstanceOf(AuthDomainException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.EMAIL_VERIFICATION_TOKEN_INVALID);

    verify(tokenRepositoryPort, never()).save(any());
    verifyNoInteractions(userRepositoryPort, auditLogEventPublisherPort);
  }

  private static EmailVerificationToken pendingToken(UUID userId) {
    return EmailVerificationToken.issue(
        UUID.randomUUID(), userId, TOKEN_HASH, NOW.minusSeconds(60), Duration.ofMinutes(15), null);
  }

  private static EmailVerificationToken expiredToken(UUID userId) {
    return EmailVerificationToken.issue(
        UUID.randomUUID(),
        userId,
        TOKEN_HASH,
        NOW.minus(Duration.ofMinutes(20)),
        Duration.ofMinutes(15),
        null);
  }

  private static EmailVerificationToken usedToken(UUID userId) {
    EmailVerificationToken token = pendingToken(userId);
    token.consume(NOW.minusSeconds(30));
    return token;
  }

  private static User pendingUser(UUID userId) {
    return User.builder()
        .id(userId)
        .username("operator1")
        .normalizedUsername("operator1")
        .email("operator1@example.com")
        .normalizedEmail("operator1@example.com")
        .displayName("Operator One")
        .passwordHash("{bcrypt}hashed")
        .status(UserStatus.PENDING_VERIFY)
        .build();
  }
}
```

- [ ] **Step 6: Rewrite `ResendVerificationEmailServiceTest`**

Replace the full contents of `src/test/java/com/vandunxg/file_processing/auth/application/service/ResendVerificationEmailServiceTest.java`:

```java
package com.vandunxg.file_processing.auth.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Optional;
import java.util.UUID;

import com.vandunxg.common.utils.HashUtils;
import com.vandunxg.file_processing.auth.application.command.ResendVerificationEmailCommand;
import com.vandunxg.file_processing.auth.application.port.out.AuditLogEventPublisherPort;
import com.vandunxg.file_processing.auth.application.port.out.EmailVerificationTokenRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.RegisterThrottlePort;
import com.vandunxg.file_processing.auth.application.port.out.UserRepositoryPort;
import com.vandunxg.file_processing.auth.application.port.out.VerificationEmailEventPublisherPort;
import com.vandunxg.file_processing.auth.application.port.out.VerificationTokenGeneratorPort;
import com.vandunxg.file_processing.auth.configuration.AuthProperties;
import com.vandunxg.file_processing.auth.domain.exception.AuthDomainException;
import com.vandunxg.file_processing.auth.domain.exception.AuthErrorCode;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import com.vandunxg.file_processing.auth.domain.model.EmailVerificationToken;
import com.vandunxg.file_processing.auth.domain.model.OperationType;
import com.vandunxg.file_processing.auth.domain.model.User;
import com.vandunxg.file_processing.auth.domain.model.UserStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@ExtendWith(MockitoExtension.class)
class ResendVerificationEmailServiceTest {

  private static final Instant NOW = Instant.parse("2026-07-17T10:15:30Z");

  @Mock private RegisterThrottlePort throttlePort;
  @Mock private UserRepositoryPort userRepositoryPort;
  @Mock private EmailVerificationTokenRepositoryPort tokenRepositoryPort;
  @Mock private AuditLogEventPublisherPort auditLogEventPublisherPort;
  @Mock private VerificationTokenGeneratorPort tokenGeneratorPort;
  @Mock private VerificationEmailEventPublisherPort verificationEmailEventPublisherPort;

  private ResendVerificationEmailService resendVerificationEmailService;

  @BeforeEach
  void setUp() {
    AuthProperties authProperties =
        new AuthProperties(
            new AuthProperties.Password("bcrypt", 10, 8, 128),
            new AuthProperties.Register(5),
            new AuthProperties.EmailVerification(
                Duration.ofMinutes(15), "https://app.example.com/verify", 5),
            new AuthProperties.Redis(
                new AuthProperties.Redis.Throttle("test:throttle:", Duration.ofHours(1)),
                new AuthProperties.Redis.EmailVerificationKeys(
                    "test:email-verify:token:", "test:email-verify:user:")),
            new AuthProperties.Amqp(
                "test.auth.events",
                new AuthProperties.Amqp.RoutingKey("test.audit-log", "test.verification-email"),
                new AuthProperties.Amqp.Queue("test.audit-log.queue", "test.verification-email.queue")));
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    resendVerificationEmailService =
        new ResendVerificationEmailService(
            throttlePort,
            userRepositoryPort,
            tokenRepositoryPort,
            auditLogEventPublisherPort,
            tokenGeneratorPort,
            verificationEmailEventPublisherPort,
            authProperties,
            clock);
  }

  @AfterEach
  void tearDown() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  @Test
  void resendIsSilentNoOpWhenIdentifierUnknown() {
    ResendVerificationEmailCommand command = command("nobody@example.com");
    givenThrottleAllows();
    when(userRepositoryPort.findByNormalizedIdentifier("nobody@example.com"))
        .thenReturn(Optional.empty());

    resendVerificationEmailService.resend(command);

    verifyNoInteractions(
        tokenRepositoryPort, auditLogEventPublisherPort, verificationEmailEventPublisherPort);
  }

  @Test
  void resendIsSilentNoOpWhenAccountAlreadyActive() {
    ResendVerificationEmailCommand command = command("operator1@example.com");
    givenThrottleAllows();
    when(userRepositoryPort.findByNormalizedIdentifier("operator1@example.com"))
        .thenReturn(Optional.of(activeUser(UUID.randomUUID())));

    resendVerificationEmailService.resend(command);

    verifyNoInteractions(
        tokenRepositoryPort, auditLogEventPublisherPort, verificationEmailEventPublisherPort);
  }

  @Test
  void resendInvalidatesOldTokensIssuesNewTokenAndPublishesEventsAfterCommitWhenAccountPending() {
    UUID userId = UUID.randomUUID();
    User pendingUser = pendingUser(userId);
    ResendVerificationEmailCommand command = command("operator1@example.com");
    givenThrottleAllows();
    when(userRepositoryPort.findByNormalizedIdentifier("operator1@example.com"))
        .thenReturn(Optional.of(pendingUser));
    when(tokenGeneratorPort.generate()).thenReturn("new-raw-token");
    when(tokenRepositoryPort.save(any(EmailVerificationToken.class)))
        .thenAnswer(invocation -> invocation.getArgument(0));

    TransactionSynchronizationManager.initSynchronization();

    resendVerificationEmailService.resend(command);

    verify(tokenRepositoryPort).invalidateAllForUser(userId, NOW);

    ArgumentCaptor<EmailVerificationToken> tokenCaptor =
        ArgumentCaptor.forClass(EmailVerificationToken.class);
    verify(tokenRepositoryPort).save(tokenCaptor.capture());
    String expectedHash = HashUtils.sha256("new-raw-token".getBytes(StandardCharsets.UTF_8));
    assertThat(tokenCaptor.getValue().getTokenHash()).isEqualTo(expectedHash);
    assertThat(tokenCaptor.getValue().getTokenHash()).isNotEqualTo("new-raw-token");
    assertThat(tokenCaptor.getValue().getUserId()).isEqualTo(userId);

    verifyNoInteractions(auditLogEventPublisherPort, verificationEmailEventPublisherPort);

    new ArrayList<>(TransactionSynchronizationManager.getSynchronizations())
        .forEach(TransactionSynchronization::afterCommit);

    ArgumentCaptor<AuditLog> auditCaptor = ArgumentCaptor.forClass(AuditLog.class);
    verify(auditLogEventPublisherPort).publish(auditCaptor.capture());
    assertThat(auditCaptor.getValue().getOperation())
        .isEqualTo(OperationType.EMAIL_VERIFICATION_REQUESTED);
    assertThat(auditCaptor.getValue().getObjectId()).isEqualTo(userId);
    assertThat(auditCaptor.getValue().getChangedBy()).isEqualTo(userId);

    verify(verificationEmailEventPublisherPort)
        .publish(
            "operator1@example.com",
            "Operator One",
            "https://app.example.com/verify?token=new-raw-token");
  }

  @Test
  void resendThrowsRateLimitedBeforeIdentifierLookupWhenThrottleExceeded() {
    ResendVerificationEmailCommand command = command("operator1@example.com");
    when(throttlePort.tryConsume(eq("resend:" + command.getIpAddress()), eq(5))).thenReturn(false);

    assertThatThrownBy(() -> resendVerificationEmailService.resend(command))
        .isInstanceOf(AuthDomainException.class)
        .extracting("error")
        .isEqualTo(AuthErrorCode.AUTH_RATE_LIMITED);

    verifyNoInteractions(
        userRepositoryPort,
        tokenRepositoryPort,
        auditLogEventPublisherPort,
        tokenGeneratorPort,
        verificationEmailEventPublisherPort);
  }

  private void givenThrottleAllows() {
    when(throttlePort.tryConsume(anyString(), eq(5))).thenReturn(true);
  }

  private static ResendVerificationEmailCommand command(String identifier) {
    return ResendVerificationEmailCommand.builder()
        .identifier(identifier)
        .ipAddress("203.0.113.5")
        .build();
  }

  private static User pendingUser(UUID userId) {
    return User.builder()
        .id(userId)
        .username("operator1")
        .normalizedUsername("operator1")
        .email("operator1@example.com")
        .normalizedEmail("operator1@example.com")
        .displayName("Operator One")
        .passwordHash("{bcrypt}hashed")
        .status(UserStatus.PENDING_VERIFY)
        .build();
  }

  private static User activeUser(UUID userId) {
    return User.builder()
        .id(userId)
        .username("operator2")
        .normalizedUsername("operator2")
        .email("operator2@example.com")
        .normalizedEmail("operator2@example.com")
        .displayName("Operator Two")
        .passwordHash("{bcrypt}hashed")
        .status(UserStatus.ACTIVE)
        .build();
  }
}
```

- [ ] **Step 7: Run the three unit test classes**

```bash
./mvnw -Dtest=RegisterServiceTest,VerifyEmailServiceTest,ResendVerificationEmailServiceTest test
```

Expected: BUILD SUCCESS.

- [ ] **Step 8: Full compile to confirm nothing else references the removed constructor shapes**

```bash
./mvnw -DskipTests clean compile
```

Expected: BUILD SUCCESS. (`AuditLogPort`/`EmailSenderPort` are still valid types — only these three services stop depending on them directly; Task 5 wires their remaining consumers.)

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/vandunxg/file_processing/auth/application/service/RegisterService.java \
        src/main/java/com/vandunxg/file_processing/auth/application/service/VerifyEmailService.java \
        src/main/java/com/vandunxg/file_processing/auth/application/service/ResendVerificationEmailService.java \
        src/test/java/com/vandunxg/file_processing/auth/application/service/RegisterServiceTest.java \
        src/test/java/com/vandunxg/file_processing/auth/application/service/VerifyEmailServiceTest.java \
        src/test/java/com/vandunxg/file_processing/auth/application/service/ResendVerificationEmailServiceTest.java
git commit -m "$(cat <<'EOF'
refactor(auth): publish audit-log and verification-email events instead of direct port calls

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: RabbitMQ Inbound Listeners

**Files:**
- Create: `src/main/java/com/vandunxg/file_processing/auth/adapter/in/amqp/AuditLogEventListener.java`
- Create: `src/main/java/com/vandunxg/file_processing/auth/adapter/in/amqp/VerificationEmailEventListener.java`
- Create: `src/test/java/com/vandunxg/file_processing/auth/adapter/in/amqp/AuditLogEventListenerTest.java`
- Create: `src/test/java/com/vandunxg/file_processing/auth/adapter/in/amqp/VerificationEmailEventListenerTest.java`

**Interfaces:**
- Consumes: `AuditLogPort.record(AuditLog)` (unchanged, existing port implemented by `AuditLogPersistenceAdapter`); `EmailSenderPort.sendVerificationEmail(String, String, String)` (unchanged, existing port implemented by `MailServiceEmailSenderAdapter`); `AuthProperties.amqp().queue().auditLog()/.verificationEmail()` (from Task 3); `SendVerificationEmailEvent` (from Task 3).
- Produces: nothing consumed by later tasks beyond being wired into the running application.

- [ ] **Step 1: Write the audit log listener**

`src/main/java/com/vandunxg/file_processing/auth/adapter/in/amqp/AuditLogEventListener.java`:

```java
package com.vandunxg.file_processing.auth.adapter.in.amqp;

import com.vandunxg.file_processing.auth.application.port.out.AuditLogPort;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-AUDIT-LOG-LISTENER")
public class AuditLogEventListener {

  private final AuditLogPort auditLogPort;

  @RabbitListener(queues = "${app.auth.amqp.queue.audit-log}")
  public void onAuditLogEvent(AuditLog auditLog) {
    log.debug("[onAuditLogEvent] received audit log event objectId={}", auditLog.getObjectId());
    auditLogPort.record(auditLog);
  }
}
```

- [ ] **Step 2: Write the verification email listener**

`src/main/java/com/vandunxg/file_processing/auth/adapter/in/amqp/VerificationEmailEventListener.java`:

```java
package com.vandunxg.file_processing.auth.adapter.in.amqp;

import com.vandunxg.file_processing.auth.application.port.out.EmailSenderPort;
import com.vandunxg.file_processing.auth.domain.event.SendVerificationEmailEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-EMAIL-LISTENER")
public class VerificationEmailEventListener {

  private final EmailSenderPort emailSenderPort;

  @RabbitListener(queues = "${app.auth.amqp.queue.verification-email}")
  public void onSendVerificationEmailEvent(SendVerificationEmailEvent event) {
    // Never log event.verificationLink(): it carries the raw opaque token.
    log.debug("[onSendVerificationEmailEvent] received verification email event");
    emailSenderPort.sendVerificationEmail(
        event.toEmail(), event.displayName(), event.verificationLink());
  }
}
```

- [ ] **Step 3: Write the audit log listener unit test**

`src/test/java/com/vandunxg/file_processing/auth/adapter/in/amqp/AuditLogEventListenerTest.java`:

```java
package com.vandunxg.file_processing.auth.adapter.in.amqp;

import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.UUID;

import com.vandunxg.file_processing.auth.application.port.out.AuditLogPort;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import com.vandunxg.file_processing.auth.domain.model.AuditLogDomain;
import com.vandunxg.file_processing.auth.domain.model.OperationType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AuditLogEventListenerTest {

  @Mock private AuditLogPort auditLogPort;

  @Test
  void onAuditLogEvent_delegatesToAuditLogPort() {
    AuditLogEventListener listener = new AuditLogEventListener(auditLogPort);
    AuditLog auditLog =
        AuditLog.builder()
            .id(UUID.randomUUID())
            .domain(AuditLogDomain.AUTH)
            .objectId(UUID.randomUUID())
            .operation(OperationType.USER_REGISTERED)
            .changedBy(UUID.randomUUID())
            .changedAt(Instant.now())
            .build();

    listener.onAuditLogEvent(auditLog);

    verify(auditLogPort).record(auditLog);
  }
}
```

- [ ] **Step 4: Write the verification email listener unit test**

`src/test/java/com/vandunxg/file_processing/auth/adapter/in/amqp/VerificationEmailEventListenerTest.java`:

```java
package com.vandunxg.file_processing.auth.adapter.in.amqp;

import static org.mockito.Mockito.verify;

import com.vandunxg.file_processing.auth.application.port.out.EmailSenderPort;
import com.vandunxg.file_processing.auth.domain.event.SendVerificationEmailEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VerificationEmailEventListenerTest {

  @Mock private EmailSenderPort emailSenderPort;

  @Test
  void onSendVerificationEmailEvent_delegatesToEmailSenderPort() {
    VerificationEmailEventListener listener = new VerificationEmailEventListener(emailSenderPort);
    SendVerificationEmailEvent event =
        new SendVerificationEmailEvent(
            "operator1@example.com", "Operator One", "https://app.example.com/verify?token=raw");

    listener.onSendVerificationEmailEvent(event);

    verify(emailSenderPort)
        .sendVerificationEmail(
            "operator1@example.com",
            "Operator One",
            "https://app.example.com/verify?token=raw");
  }
}
```

- [ ] **Step 5: Run the two new listener tests**

```bash
./mvnw -Dtest=AuditLogEventListenerTest,VerificationEmailEventListenerTest test
```

Expected: BUILD SUCCESS.

- [ ] **Step 6: Compile the full project**

```bash
./mvnw -DskipTests clean compile
```

Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/vandunxg/file_processing/auth/adapter/in/amqp/AuditLogEventListener.java \
        src/main/java/com/vandunxg/file_processing/auth/adapter/in/amqp/VerificationEmailEventListener.java \
        src/test/java/com/vandunxg/file_processing/auth/adapter/in/amqp/AuditLogEventListenerTest.java \
        src/test/java/com/vandunxg/file_processing/auth/adapter/in/amqp/VerificationEmailEventListenerTest.java
git commit -m "$(cat <<'EOF'
feat(auth): consume audit-log and verification-email events in-process via RabbitMQ listeners

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: End-to-End Integration Test Wiring

**Files:**
- Modify: `pom.xml`
- Modify: `src/test/java/com/vandunxg/file_processing/auth/adapter/in/web/AuthControllerIT.java`

**Interfaces:**
- Consumes: everything from Tasks 1-5 wired together in a real Spring context.
- Produces: nothing — this is the terminal end-to-end verification for the whole plan (Task 7 is a final cross-cutting check, not a new capability).

- [ ] **Step 1: Add Testcontainers RabbitMQ module and Awaitility to `pom.xml`**

In `pom.xml`, add two new `test`-scope dependencies near the existing `org.testcontainers:postgresql` entry:

```xml
    <dependency>
      <groupId>org.testcontainers</groupId>
      <artifactId>rabbitmq</artifactId>
      <version>${testcontainers.version}</version>
      <scope>test</scope>
    </dependency>

    <dependency>
      <groupId>org.awaitility</groupId>
      <artifactId>awaitility</artifactId>
      <version>4.2.2</version>
      <scope>test</scope>
    </dependency>
```

- [ ] **Step 2: Rewrite `AuthControllerIT`**

Replace the full contents of `src/test/java/com/vandunxg/file_processing/auth/adapter/in/web/AuthControllerIT.java`:

```java
package com.vandunxg.file_processing.auth.adapter.in.web;

import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.request.RegisterRequest;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.request.ResendVerificationRequest;
import com.vandunxg.file_processing.auth.adapter.in.web.dto.request.VerifyEmailRequest;
import com.vandunxg.file_processing.auth.application.port.out.EmailSenderPort;
import com.vandunxg.file_processing.testsupport.PostgresIntegrationTest;
import com.vandunxg.file_processing.testsupport.PostgresTestContainerBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Covers the public register / verify-email / resend-verification HTTP contract end to end against
 * a real Postgres, Redis, and RabbitMQ. Each test method (other than the dedicated throttle test)
 * uses a distinct fake client IP via the {@code X-Real-IP} header so the shared Redis sliding-window
 * throttle counter ({@link
 * com.vandunxg.file_processing.auth.adapter.out.cache.RedisRegisterThrottleAdapter}) does not leak
 * attempts between unrelated test methods sharing the same Spring context.
 *
 * <p>{@code app.auth.register.max-attempts-per-hour} is lowered just for this test class (via
 * {@link TestPropertySource}, not the shared {@code application-test.yml}) so the 429 case can be
 * reached with a handful of requests instead of the production-matching default of 10.
 *
 * <p>Audit-log recording and verification-email sending now happen asynchronously via RabbitMQ
 * (publish in the HTTP request thread, consume on a separate listener container thread), so the test
 * that needs the captured verification link polls with Awaitility instead of asserting immediately
 * after the HTTP response returns.
 */
@PostgresIntegrationTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.auth.register.max-attempts-per-hour=3")
class AuthControllerIT extends PostgresTestContainerBase {

  private static final String BASE_URL = "/api/v1/auth";

  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
          .withExposedPorts(6379)
          .withReuse(true);

  private static final RabbitMQContainer RABBITMQ =
      new RabbitMQContainer(DockerImageName.parse("rabbitmq:4-management-alpine")).withReuse(true);

  static {
    REDIS.start();
    RABBITMQ.start();
  }

  @DynamicPropertySource
  static void infraProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", REDIS::getHost);
    registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
    registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
    registry.add("spring.rabbitmq.username", RABBITMQ::getAdminUsername);
    registry.add("spring.rabbitmq.password", RABBITMQ::getAdminPassword);
  }

  @Autowired private MockMvc mockMvc;
  @Autowired private ObjectMapper objectMapper;
  @Autowired private CapturingEmailSenderPort capturingEmailSenderPort;

  @Test
  void register_returns201WithPendingVerifyStatus_whenRequestValid() throws Exception {
    RegisterRequest request =
        registerRequest("reg-ok", "reg-ok@example.com", "Register Ok", "StrongPassw0rd!");

    mockMvc
        .perform(registerCall(request, "203.0.113.10"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.data.username").value("reg-ok"))
        .andExpect(jsonPath("$.data.email").value("reg-ok@example.com"))
        .andExpect(jsonPath("$.data.displayName").value("Register Ok"))
        .andExpect(jsonPath("$.data.status").value("PENDING_VERIFY"))
        .andExpect(jsonPath("$.data.id").isNotEmpty());
  }

  @Test
  void register_returns409_whenUsernameAlreadyExists() throws Exception {
    String ip = "203.0.113.11";
    RegisterRequest first =
        registerRequest("dup-user", "dup-user-1@example.com", "Dup User", "StrongPassw0rd!");
    mockMvc.perform(registerCall(first, ip)).andExpect(status().isCreated());

    RegisterRequest second =
        registerRequest("dup-user", "dup-user-2@example.com", "Dup User", "StrongPassw0rd!");
    mockMvc.perform(registerCall(second, ip)).andExpect(status().isConflict());
  }

  @Test
  void register_returns409_whenEmailAlreadyExists() throws Exception {
    String ip = "203.0.113.12";
    RegisterRequest first =
        registerRequest("dup-email-1", "dup-email@example.com", "Dup Email", "StrongPassw0rd!");
    mockMvc.perform(registerCall(first, ip)).andExpect(status().isCreated());

    RegisterRequest second =
        registerRequest("dup-email-2", "dup-email@example.com", "Dup Email", "StrongPassw0rd!");
    mockMvc.perform(registerCall(second, ip)).andExpect(status().isConflict());
  }

  @Test
  void register_returns400_whenPasswordViolatesPolicy() throws Exception {
    RegisterRequest request =
        registerRequest("weak-pass", "weak-pass@example.com", "Weak Pass", "short1");

    mockMvc.perform(registerCall(request, "203.0.113.13")).andExpect(status().isBadRequest());
  }

  @Test
  void register_returns429_afterExceedingConfiguredPerHourLimit() throws Exception {
    String ip = "203.0.113.14";

    for (int i = 0; i < 3; i++) {
      RegisterRequest request =
          registerRequest(
              "throttle-" + i,
              "throttle-" + i + "@example.com",
              "Throttle User",
              "StrongPassw0rd!");
      mockMvc.perform(registerCall(request, ip)).andExpect(status().isCreated());
    }

    RegisterRequest overLimit =
        registerRequest(
            "throttle-over", "throttle-over@example.com", "Throttle Over", "StrongPassw0rd!");
    mockMvc.perform(registerCall(overLimit, ip)).andExpect(status().isTooManyRequests());
  }

  @Test
  void verifyEmail_returns200WithActiveStatus_whenTokenValid() throws Exception {
    String ip = "203.0.113.15";
    RegisterRequest registerRequest =
        registerRequest("verify-ok", "verify-ok@example.com", "Verify Ok", "StrongPassw0rd!");
    mockMvc.perform(registerCall(registerRequest, ip)).andExpect(status().isCreated());

    await()
        .atMost(Duration.ofSeconds(10))
        .until(capturingEmailSenderPort::hasVerificationLink);
    String rawToken = extractToken(capturingEmailSenderPort.lastVerificationLink());
    VerifyEmailRequest verifyEmailRequest = new VerifyEmailRequest();
    verifyEmailRequest.setToken(rawToken);

    mockMvc
        .perform(
            post(BASE_URL + "/verify-email")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(verifyEmailRequest)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.status").value("ACTIVE"));
  }

  @Test
  void verifyEmail_returns400_whenTokenUnknown() throws Exception {
    VerifyEmailRequest verifyEmailRequest = new VerifyEmailRequest();
    verifyEmailRequest.setToken("plausible-but-unknown-token-1234567890abcdef");

    mockMvc
        .perform(
            post(BASE_URL + "/verify-email")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(verifyEmailRequest)))
        .andExpect(status().isBadRequest());
  }

  @Test
  void resendVerification_returns204_whenIdentifierUnknown() throws Exception {
    ResendVerificationRequest request = new ResendVerificationRequest();
    request.setIdentifier("no-such-account@example.com");

    mockMvc
        .perform(
            post(BASE_URL + "/resend-verification")
                .header("X-Real-IP", "203.0.113.16")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isNoContent());
  }

  @Test
  void resendVerification_returns204_whenIdentifierIsRealPendingAccount() throws Exception {
    String ip = "203.0.113.17";
    RegisterRequest registerRequest =
        registerRequest("resend-ok", "resend-ok@example.com", "Resend Ok", "StrongPassw0rd!");
    mockMvc.perform(registerCall(registerRequest, ip)).andExpect(status().isCreated());

    ResendVerificationRequest resendRequest = new ResendVerificationRequest();
    resendRequest.setIdentifier("resend-ok@example.com");

    mockMvc
        .perform(
            post(BASE_URL + "/resend-verification")
                .header("X-Real-IP", ip)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(resendRequest)))
        .andExpect(status().isNoContent());
  }

  private MockHttpServletRequestBuilder registerCall(RegisterRequest request, String ip)
      throws Exception {
    return post(BASE_URL + "/register")
        .header("X-Real-IP", ip)
        .contentType(MediaType.APPLICATION_JSON)
        .content(objectMapper.writeValueAsString(request));
  }

  private static RegisterRequest registerRequest(
      String username, String email, String displayName, String password) {
    RegisterRequest request = new RegisterRequest();
    request.setUsername(username);
    request.setEmail(email);
    request.setDisplayName(displayName);
    request.setPassword(password);
    return request;
  }

  private static String extractToken(String verificationLink) {
    String query = URI.create(verificationLink).getQuery();
    return Arrays.stream(query.split("&"))
        .filter(param -> param.startsWith("token="))
        .map(param -> param.substring("token=".length()))
        .findFirst()
        .orElseThrow(
            () -> new IllegalStateException("no token query parameter in " + verificationLink));
  }

  @TestConfiguration
  static class CapturingEmailSenderConfig {

    @Bean
    @Primary
    CapturingEmailSenderPort capturingEmailSenderPort() {
      return new CapturingEmailSenderPort();
    }
  }

  /**
   * Test-only {@link EmailSenderPort} double that captures the last verification link instead of
   * sending real email, so the IT can pull the raw token out of it (the controller never returns
   * the raw token, correctly, since it is a secret). Now invoked from {@code
   * VerificationEmailEventListener} rather than directly from the service, hence the {@code
   * hasVerificationLink} poll helper used by the caller.
   */
  static class CapturingEmailSenderPort implements EmailSenderPort {

    private final List<String> verificationLinks = new CopyOnWriteArrayList<>();

    @Override
    public void sendVerificationEmail(String toEmail, String displayName, String verificationLink) {
      verificationLinks.add(verificationLink);
    }

    boolean hasVerificationLink() {
      return !verificationLinks.isEmpty();
    }

    String lastVerificationLink() {
      return verificationLinks.get(verificationLinks.size() - 1);
    }
  }
}
```

- [ ] **Step 3: Run the full integration test class**

```bash
./mvnw -Dtest=AuthControllerIT test
```

Expected: BUILD SUCCESS (requires Docker available for Testcontainers: Postgres, Redis, RabbitMQ all start).

- [ ] **Step 4: Run the complete test suite**

```bash
./mvnw verify
```

Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add pom.xml src/test/java/com/vandunxg/file_processing/auth/adapter/in/web/AuthControllerIT.java
git commit -m "$(cat <<'EOF'
test(auth): wire Redis and RabbitMQ Testcontainers into AuthControllerIT

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: Final Verification Pass

**Files:** none new — review only.

- [ ] **Step 1: Format**

```bash
./mvnw spotless:apply
```

- [ ] **Step 2: Full build**

```bash
./mvnw verify
```

Expected: BUILD SUCCESS (Docker required for Testcontainers-backed integration tests).

- [ ] **Step 3: Confirm no raw secret ever reaches a log line**

```bash
grep -rn "log\.\(info\|debug\|warn\|error\)" src/main/java/com/vandunxg/file_processing/auth/adapter/out/amqp src/main/java/com/vandunxg/file_processing/auth/adapter/in/amqp src/main/java/com/vandunxg/file_processing/auth/adapter/out/cache | grep -i "verificationLink\|rawToken\|token.getTokenHash\|password"
```

Expected: no output (or only matches that are clearly not logging the raw value — inspect any hit manually). None of the new files should log `verificationLink`, a raw token, or a password.

- [ ] **Step 4: Confirm no new `AuthErrorCode` was introduced and i18n is untouched**

```bash
git diff --stat main -- src/main/resources/i18n/
```

Expected: empty output — this plan reuses only existing error codes (`AUTH_RATE_LIMITED`, `EMAIL_VERIFICATION_TOKEN_INVALID`, etc.), so no i18n file should have changed.

- [ ] **Step 5: Confirm the old Postgres table is genuinely untouched**

```bash
git diff --stat main -- src/main/resources/db/migration/
```

Expected: empty output — no new or edited Flyway migration, per the explicit decision to leave `auth_email_verification_tokens` in place, unused.

- [ ] **Step 6: Commit any final formatting-only correction separately, if Step 1 changed anything beyond what earlier tasks already committed**

```bash
git add -u
git commit -m "$(cat <<'EOF'
style: spotless apply

Co-Authored-By: Claude Sonnet 5 <noreply@anthropic.com>
EOF
)"
```
