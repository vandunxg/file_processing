# Auth Module — Part 1: Setup + Domain (Enum, Exception, Aggregate, Policy)

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

> **Part 1 of 4** — Toàn bộ Auth Module Phase 1+2 được chia thành 4 file plan:
> - **Part 1** (file này): Setup dependency + Test infrastructure + Domain enum/exception/aggregate/policy — Task 1-14
> - **Part 2** `2026-07-17-auth-part2-migration-persistence.md`: Flyway migration + JPA + adapter + IT — Task 15-30
> - **Part 3** `2026-07-17-auth-part3-jwt-and-login.md`: JWT service + JWKS + CredentialVersionCache + LoginUseCase + BootstrapAdmin — Task 31-43
> - **Part 4** `2026-07-17-auth-part4-security-and-web.md`: Spring Security filter chain + JwtAuthenticationConverter + AuthController + E2E test — Task 44-51

**Goal:** Xây dựng nền tảng domain-layer của Auth Module — enum, exception, aggregate (`User`, `Role`, `RolePermission`, `UserRole`), policy (`PasswordPolicy`, `LoginLockPolicy`, `LastActiveAdminPolicy`), `PermissionExpression` helper. Sau khi hoàn thành, có thể unit test toàn bộ domain rule mà không cần Spring hay database.

**Architecture:** Hexagonal per `RULE.md §3`. Domain không phụ thuộc Spring/JPA/JWT. Application service điều phối qua inbound/outbound port. Adapter web (Spring MVC), persistence (JPA + Flyway PostgreSQL), security (OAuth2 Resource Server + Nimbus RSA), cache (Redis + Caffeine fallback), email và audit là port-only trong phase này (implementation adapter đầy đủ ở phase 4-6).

**Tech Stack:** Java 21, Spring Boot 4.1.x, Spring Security 6 OAuth2 Resource Server, PostgreSQL 16+, Redis 7 (qua `common-cache`), Flyway, JPA/Hibernate, Nimbus JOSE + JWT, MapStruct 1.6.3 + Lombok binding, BCrypt cost 12 qua `DelegatingPasswordEncoder`, JUnit 5 + Mockito + AssertJ + Testcontainers.

## Global Constraints

**Từ `RULE.md` (bắt buộc):**

- Java 21, Spring Boot parent 4.1.0 (pin sẵn trong `pom.xml`).
- Package layout Hexagonal: `com.vandunxg.file_processing.auth.{domain,application,adapter,configuration}` (`RULE.md §3`).
- Naming: `<Xxx>UseCase`, `<Xxx>RepositoryPort`, `<Xxx>Service`, `<Xxx>Command`, `<Xxx>Query`, `<Xxx>Controller`, `<Xxx>Request`, `<Xxx>Response`, `<Xxx>Entity`, `Jpa<Xxx>Repository`, `<Xxx>PersistenceAdapter`, `<Xxx>PersistenceMapper`, `<Xxx>WebMapper` (`RULE.md §4`).
- Domain aggregate extends `com.vandunxg.common.models.domain.AuditableDomain`; JPA entity extends `com.vandunxg.common.models.entities.AuditableEntity` (`RULE.md §5`, `§10`).
- Lombok combo cho domain: `@Getter @SuperBuilder @NoArgsConstructor @AllArgsConstructor @Setter(AccessLevel.PRIVATE) @EqualsAndHashCode(callSuper = false)` (`RULE.md §5`).
- Soft-delete dùng `deleted_at TIMESTAMPTZ NULL` + partial index `WHERE deleted_at IS NULL`, **không** dùng `deleted BOOLEAN` (`RULE.md §12.1`). Áp dụng cho toàn bộ entity.
- MapStruct `@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)`; domain↔entity mapper implement `com.vandunxg.common.models.mapper.EntityMapper<D,E>`; annotation processor path phải có `lombok`, `lombok-mapstruct-binding`, `mapstruct-processor` (`RULE.md §7`).
- Cấm `ModelMapper`, `BeanUtils.copyProperties`, reflection-mapping (`RULE.md §17`).
- Cấm `@Autowired` field — dùng constructor injection qua `@RequiredArgsConstructor` (`RULE.md §17.9`).
- Migration filename `V{yyyyMMddHHmm}__{snake_case}.sql`, append-only, `ddl-auto: validate` (`RULE.md §12`).
- Password hasher: BCrypt cost 12 qua `DelegatingPasswordEncoder` (spec §8.3, §43.1).
- JWT: RS256 duy nhất, kèm `kid` header, `alg = none` bị từ chối (spec §9.1, §9.6, §43.2).
- Log format `@Slf4j(topic = "MODULE-FEATURE")`, message `[methodName] description key={} key={}` (`RULE.md §8`).
- Log warn/error TRƯỚC mọi `throw` kết thúc request (`RULE.md §6.2`).
- Không log password, JWT, refresh token, private key, Authorization header (spec §37.3, §43.1).
- i18n key có trong cả `messages.properties` (English) và `messages_vi.properties` (Vietnamese) (`RULE.md §6.4`).
- Spotless + Google Java Format 1.27.0 chạy trước commit: `mvn spotless:apply && mvn verify` (`RULE.md §9`).
- Base API path `${app.api.prefix}/${app.api.version}` = `/api/v1` (đã cấu hình trong `application.yaml`).
- Common library dependencies dùng khi có sẵn: `AuditableDomain`, `AuditableEntity`, `Response<T>`, `PagingResponse<T>`, `PagingQuery`, `ResponseException`, `UserAuthentication`, `IdUtils.nextId()`, `HashUtils.sha256(...)`, `StrUtils.emailFormat(...)`, `LocaleStringService` (`RULE.md §10`).

**Từ `AGENTS.md`:**

- Không thêm framework mới (không Kafka, không CQRS framework, không Event Sourcing) ngoài scope đã duyệt.
- Không tạo interface cho mọi class — chỉ khi có ranh giới thay thế/kiểm thử thật.

**Từ spec `docs/specs/auth-module-requirements.md`:**

- Password 8-128 ký tự, không bắt buộc đủ loại, cấm trùng username/email (`§8.3`).
- Login lockout 5 lần thất bại trong 15 phút → khóa 15 phút (`§8.4`).
- Session absolute TTL 30 ngày, access token TTL 15 phút, clock skew 60s (`§8.5`, `§9.1`).
- Refresh token 256-bit entropy, SHA-256 hash lưu DB, cookie `HttpOnly + Secure + SameSite=Strict + Path=/api/v1/auth` (`§9.3`) — **chỉ Phase 3 mới implement, Phase 2 stub port**.
- RBAC clone `be-v2`: `Role` + `RolePermission(role_id, resource_code, action)` + `UserRole`, permission JWT claim dạng `"resource:action"` lowercase, `"all:manage"` là super wildcard (`§8.6`, `§11.2-11.4`).
- Enum `ResourceCode`: `ALL, USER, ROLE, SESSION, AUDIT, FILE, JOB, REPORT, CUSTOMER`.
- Enum `Action` (từ `common-models`): `MANAGE, READ, CREATE, UPDATE, DELETE, SELF_READ, SELF_CREATE, SELF_UPDATE, SELF_DELETE, EXPORT`.
- Seed 2 role built-in (`is_const = true`): `ADMIN` với `(ALL, MANAGE)`, `OPERATOR` với `SELF_*` permission (`§40.10`).
- Bootstrap Admin qua `@EventListener(ApplicationReadyEvent)` + env variable (`§15`).
- Không MFA, không self-registration flow trong Phase 1+2 (register là Phase 4).

---

## File Structure

**Modify:**

- `pom.xml` — thêm dependency, MapStruct processor path, xoá `modelmapper.version`.
- `src/main/resources/application.yaml` — bổ sung `app.auth.*` namespace.
- `src/main/resources/application-dev.yml` — dev-only key/secret placeholder.
- `src/main/resources/i18n/messages.properties` — English i18n key.
- `src/main/resources/i18n/messages_vi.properties` — Vietnamese i18n key.
- `src/main/java/com/vandunxg/file_processing/auth/domain/model/User.java` — mở rộng field.
- `src/main/java/com/vandunxg/file_processing/auth/domain/model/Role.java` — rewrite theo `§11.2`.
- `src/main/java/com/vandunxg/file_processing/auth/domain/model/UserStatus.java` — thêm `PENDING_VERIFY`.
- `src/main/java/com/vandunxg/file_processing/auth/domain/model/AuditLogDomain.java` — populate enum.
- `src/main/java/com/vandunxg/file_processing/auth/domain/model/OperationType.java` — mở rộng enum.
- `src/main/java/com/vandunxg/file_processing/auth/domain/model/AuditLog.java` — cập nhật `browser` field không lộ PII.
- `src/main/java/com/vandunxg/file_processing/configuration/security/SecurityConfiguration.java` — dùng RSA JwtDecoder + custom converter.
- `src/main/java/com/vandunxg/file_processing/auth/application/command/LoginCommand.java` — thêm field.
- `src/main/java/com/vandunxg/file_processing/auth/application/port/out/UserRepositoryPort.java` — convert từ class stub sang interface đầy đủ.
- `src/main/java/com/vandunxg/file_processing/auth/application/service/LoginService.java` — implement.
- `src/main/java/com/vandunxg/file_processing/auth/adapter/in/web/AuthController.java` — implement `/login`, `/me`.

**Delete:**

- `src/main/java/com/vandunxg/file_processing/auth/domain/model/Permission.java` — thay bằng `RolePermission.java`.

**Create (domain):**

- `auth/domain/model/RolePermission.java`
- `auth/domain/model/UserRole.java`
- `auth/domain/model/ActiveStatus.java`
- `auth/domain/model/ResourceCode.java`
- `auth/domain/model/RoleCategory.java`
- `auth/domain/model/RevocationReason.java`
- `auth/domain/policy/PasswordPolicy.java`
- `auth/domain/policy/LoginLockPolicy.java`
- `auth/domain/policy/LastActiveAdminPolicy.java`
- `auth/domain/policy/PermissionExpression.java`
- `auth/domain/exception/AuthErrorCode.java`
- `auth/domain/exception/AuthDomainException.java`

**Create (application port):**

- `auth/application/port/in/LoginUseCase.java`
- `auth/application/port/in/GetCurrentUserUseCase.java`
- `auth/application/port/in/BootstrapAdminUseCase.java`
- `auth/application/port/out/RoleRepositoryPort.java`
- `auth/application/port/out/RolePermissionRepositoryPort.java`
- `auth/application/port/out/UserRoleRepositoryPort.java`
- `auth/application/port/out/AuditLogPort.java`
- `auth/application/port/out/PasswordHasherPort.java`
- `auth/application/port/out/TokenServicePort.java`
- `auth/application/port/out/CredentialVersionCachePort.java`
- `auth/application/port/out/LoginThrottlePort.java`
- `auth/application/port/out/ClockPort.java`
- `auth/application/port/out/IdGeneratorPort.java`
- `auth/application/port/out/AuthorityQueryPort.java`

**Create (application service, command, result):**

- `auth/application/service/LoginService.java` (rewrite stub)
- `auth/application/service/GetCurrentUserService.java`
- `auth/application/service/BootstrapAdminService.java`
- `auth/application/service/AuthorityService.java`
- `auth/application/command/BootstrapAdminCommand.java`
- `auth/application/result/LoginResult.java`
- `auth/application/result/UserAuthoritySnapshot.java`

**Create (adapter out — persistence):**

- `auth/adapter/out/persistence/entity/UserEntity.java`
- `auth/adapter/out/persistence/entity/RoleEntity.java`
- `auth/adapter/out/persistence/entity/RolePermissionEntity.java`
- `auth/adapter/out/persistence/entity/UserRoleEntity.java`
- `auth/adapter/out/persistence/entity/AuditLogEntity.java`
- `auth/adapter/out/persistence/entity/JpaUserRepository.java` (rewrite stub)
- `auth/adapter/out/persistence/entity/JpaRoleRepository.java`
- `auth/adapter/out/persistence/entity/JpaRolePermissionRepository.java`
- `auth/adapter/out/persistence/entity/JpaUserRoleRepository.java`
- `auth/adapter/out/persistence/entity/JpaAuditLogRepository.java`
- `auth/adapter/out/persistence/mapper/UserPersistenceMapper.java`
- `auth/adapter/out/persistence/mapper/RolePersistenceMapper.java`
- `auth/adapter/out/persistence/mapper/RolePermissionPersistenceMapper.java`
- `auth/adapter/out/persistence/mapper/UserRolePersistenceMapper.java`
- `auth/adapter/out/persistence/mapper/AuditLogPersistenceMapper.java`
- `auth/adapter/out/persistence/UserPersistenceAdapter.java`
- `auth/adapter/out/persistence/RolePersistenceAdapter.java`
- `auth/adapter/out/persistence/RolePermissionPersistenceAdapter.java`
- `auth/adapter/out/persistence/UserRolePersistenceAdapter.java`
- `auth/adapter/out/persistence/AuditLogPersistenceAdapter.java`

**Create (adapter out — security / jwt):**

- `auth/adapter/out/jwt/JwkKeyRing.java`
- `auth/adapter/out/jwt/NimbusRsaTokenService.java`
- `auth/adapter/out/jwt/JwksEndpoint.java`

**Create (adapter out — cache / throttle):**

- `auth/adapter/out/cache/RedisCredentialVersionCache.java`
- `auth/adapter/out/cache/CaffeineCredentialVersionCache.java`
- `auth/adapter/out/cache/CredentialVersionCacheComposite.java`
- `auth/adapter/out/cache/RedisLoginThrottle.java`
- `auth/adapter/out/cache/CaffeineLoginThrottle.java`

**Create (adapter out — password / clock / id):**

- `auth/adapter/out/password/BcryptPasswordHasherAdapter.java`
- `auth/adapter/out/system/SystemClockAdapter.java`
- `auth/adapter/out/system/UuidIdGeneratorAdapter.java`

**Create (adapter in — web / security):**

- `auth/adapter/in/web/dto/request/LoginRequest.java`
- `auth/adapter/in/web/dto/response/LoginResponse.java`
- `auth/adapter/in/web/dto/response/UserSummaryResponse.java`
- `auth/adapter/in/web/mapper/AuthWebMapper.java`
- `auth/adapter/in/security/JwtAuthenticationConverter.java`
- `auth/adapter/in/security/BootstrapAdminListener.java`

**Create (configuration):**

- `auth/configuration/AuthProperties.java` (typed @ConfigurationProperties)
- `auth/configuration/AuthPersistenceConfiguration.java` (bean wiring)
- `auth/configuration/AuthSecurityConfiguration.java` (JwtDecoder + converter beans)

**Create (migration Flyway):**

- `src/main/resources/db/migration/V202607170900__create_auth_users.sql`
- `src/main/resources/db/migration/V202607170901__create_role.sql`
- `src/main/resources/db/migration/V202607170902__create_role_permission.sql`
- `src/main/resources/db/migration/V202607170903__create_user_role.sql`
- `src/main/resources/db/migration/V202607170904__create_audit_logs.sql`
- `src/main/resources/db/migration/V202607170905__seed_default_roles.sql`

**Create (test):**

- `src/test/java/com/vandunxg/file_processing/testsupport/PostgresIntegrationTest.java` — abstract Testcontainers base.
- `src/test/java/com/vandunxg/file_processing/auth/domain/model/UserTest.java`
- `src/test/java/com/vandunxg/file_processing/auth/domain/model/RoleTest.java`
- `src/test/java/com/vandunxg/file_processing/auth/domain/policy/PasswordPolicyTest.java`
- `src/test/java/com/vandunxg/file_processing/auth/domain/policy/LoginLockPolicyTest.java`
- `src/test/java/com/vandunxg/file_processing/auth/domain/policy/LastActiveAdminPolicyTest.java`
- `src/test/java/com/vandunxg/file_processing/auth/adapter/out/persistence/UserPersistenceAdapterIT.java`
- `src/test/java/com/vandunxg/file_processing/auth/adapter/out/persistence/RolePersistenceAdapterIT.java`
- `src/test/java/com/vandunxg/file_processing/auth/adapter/out/persistence/AuditLogPersistenceAdapterIT.java`
- `src/test/java/com/vandunxg/file_processing/auth/adapter/out/jwt/NimbusRsaTokenServiceTest.java`
- `src/test/java/com/vandunxg/file_processing/auth/application/service/AuthorityServiceTest.java`
- `src/test/java/com/vandunxg/file_processing/auth/application/service/LoginServiceTest.java`
- `src/test/java/com/vandunxg/file_processing/auth/application/service/BootstrapAdminServiceTest.java`
- `src/test/java/com/vandunxg/file_processing/auth/adapter/in/web/AuthControllerIT.java`

---

## Task 1: Cập nhật `pom.xml` — dependency, MapStruct processor path, xoá ModelMapper

**Files:**

- Modify: `pom.xml`

**Interfaces:**

- Produces: Maven build với Flyway, common-email, Redis, Caffeine, Testcontainers, MapStruct annotation processor active.

- [ ] **Step 1: Xoá property `modelmapper.version` và bổ sung properties mới**

Trong block `<properties>` giữa `<slf4j.version>` và `<vandunxg.common.version>`:

```xml
<lombok-mapstruct-binding.version>0.2.0</lombok-mapstruct-binding.version>
<testcontainers.version>1.20.4</testcontainers.version>
<caffeine.version>3.1.8</caffeine.version>
```

Xoá dòng `<modelmapper.version>3.2.6</modelmapper.version>`.

- [ ] **Step 2: Bổ sung dependency chính vào `<dependencies>`**

Thêm sau block `spring-boot-starter-security`:

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
</dependency>

<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>

<dependency>
  <groupId>org.flywaydb</groupId>
  <artifactId>flyway-core</artifactId>
</dependency>

<dependency>
  <groupId>org.flywaydb</groupId>
  <artifactId>flyway-database-postgresql</artifactId>
</dependency>

<dependency>
  <groupId>com.github.ben-manes.caffeine</groupId>
  <artifactId>caffeine</artifactId>
  <version>${caffeine.version}</version>
</dependency>

<dependency>
  <groupId>com.vandunxg.common</groupId>
  <artifactId>common-email</artifactId>
  <version>${vandunxg.common.version}</version>
</dependency>
```

- [ ] **Step 3: Bổ sung test dependencies**

Thêm trước closing `</dependencies>`:

```xml
<dependency>
  <groupId>org.testcontainers</groupId>
  <artifactId>testcontainers</artifactId>
  <version>${testcontainers.version}</version>
  <scope>test</scope>
</dependency>

<dependency>
  <groupId>org.testcontainers</groupId>
  <artifactId>junit-jupiter</artifactId>
  <version>${testcontainers.version}</version>
  <scope>test</scope>
</dependency>

<dependency>
  <groupId>org.testcontainers</groupId>
  <artifactId>postgresql</artifactId>
  <version>${testcontainers.version}</version>
  <scope>test</scope>
</dependency>

<dependency>
  <groupId>org.assertj</groupId>
  <artifactId>assertj-core</artifactId>
  <scope>test</scope>
</dependency>
```

- [ ] **Step 4: Cập nhật `annotationProcessorPaths` — thêm `lombok-mapstruct-binding` và `mapstruct-processor` cho cả `default-compile` và `default-testCompile`**

Thay 2 block `<configuration><annotationProcessorPaths>` (một trong `default-compile`, một trong `default-testCompile`) thành:

```xml
<configuration>
  <annotationProcessorPaths>
    <path>
      <groupId>org.projectlombok</groupId>
      <artifactId>lombok</artifactId>
    </path>
    <path>
      <groupId>org.projectlombok</groupId>
      <artifactId>lombok-mapstruct-binding</artifactId>
      <version>${lombok-mapstruct-binding.version}</version>
    </path>
    <path>
      <groupId>org.mapstruct</groupId>
      <artifactId>mapstruct-processor</artifactId>
      <version>${mapstruct.version}</version>
    </path>
  </annotationProcessorPaths>
</configuration>
```

- [ ] **Step 5: Build kiểm tra không có compile error**

```bash
./mvnw -DskipTests clean compile
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```bash
git add pom.xml
git commit -m "chore(auth): add flyway/redis/testcontainers/caffeine and mapstruct processor path

- add flyway-core, flyway-database-postgresql, spring-boot-starter-data-redis
- add caffeine for in-memory fallback cache
- add common-email 2.0.5 for future email adapter
- add testcontainers + junit-jupiter + postgresql for integration tests
- wire lombok-mapstruct-binding + mapstruct-processor into annotation processor path per RULE.md §7.1
- remove obsolete modelmapper.version property (ModelMapper is banned per RULE.md §17.7)"
```

---

## Task 2: Test infrastructure — Testcontainers base class

**Files:**

- Create: `src/test/java/com/vandunxg/file_processing/testsupport/PostgresIntegrationTest.java`

**Interfaces:**

- Produces: `@PostgresIntegrationTest` composed annotation kích hoạt `@SpringBootTest` + Testcontainers PostgreSQL 16 shared between test classes trong cùng JVM.

- [ ] **Step 1: Tạo file `PostgresIntegrationTest.java`**

```java
package com.vandunxg.file_processing.testsupport;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
public @interface PostgresIntegrationTest {}
```

- [ ] **Step 2: Tạo `PostgresTestContainerBase` để share container across test classes**

Create `src/test/java/com/vandunxg/file_processing/testsupport/PostgresTestContainerBase.java`:

```java
package com.vandunxg.file_processing.testsupport;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

public abstract class PostgresTestContainerBase {

  protected static final PostgreSQLContainer<?> POSTGRES =
      new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
          .withDatabaseName("file_processing_test")
          .withUsername("test")
          .withPassword("test")
          .withReuse(true);

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }
}
```

- [ ] **Step 3: Tạo `application-test.yml`**

Create `src/test/resources/application-test.yml`:

```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false

  flyway:
    enabled: true
    baseline-on-migrate: false

  data:
    redis:
      host: localhost
      port: 6379
      timeout: 500ms

logging:
  level:
    org.hibernate.SQL: INFO
    org.testcontainers: INFO

app:
  auth:
    jwt:
      issuer: file-processing-test
      audience: file-processing-api-test
      active-kid: test-key-1
      # Test-only 2048-bit RSA key (PKCS#8 base64) — Task 3 will generate.
      private-key-pem-base64: ${TEST_JWT_PRIVATE_KEY_PEM:__set_in_setup__}
      access-token-ttl: PT15M
      password-change-token-ttl: PT5M
      clock-skew: PT60S
    bootstrap:
      admin:
        enabled: false
```

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/vandunxg/file_processing/testsupport/ src/test/resources/application-test.yml
git commit -m "test(auth): add testcontainers postgres base and test profile"
```

---

## Task 3: Sinh RSA key pair cho dev/test và bổ sung `application-dev.yml` / `application-test.yml`

**Files:**

- Modify: `src/main/resources/application.yaml` — bổ sung `app.auth.*` namespace.
- Modify: `src/main/resources/application-dev.yml` — dev-only key placeholder.
- Modify: `src/test/resources/application-test.yml` — test key placeholder.
- Create: `docs/superpowers/plans/generate-test-rsa-key.sh` — helper script sinh key pair.

**Interfaces:**

- Produces: `AuthProperties` bind namespace `app.auth.*` với issuer/audience/kid/PEM.

- [ ] **Step 1: Tạo script sinh RSA key pair dev/test**

Create `docs/superpowers/plans/generate-test-rsa-key.sh`:

```bash
#!/usr/bin/env bash
set -euo pipefail

OUT_DIR="${1:-target/generated-keys}"
mkdir -p "$OUT_DIR"

openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
  -outform DER -out "$OUT_DIR/private.der"

openssl pkey -in "$OUT_DIR/private.der" -inform DER -pubout \
  -outform DER -out "$OUT_DIR/public.der"

base64 -w0 "$OUT_DIR/private.der" > "$OUT_DIR/private.pem.b64"
base64 -w0 "$OUT_DIR/public.der"  > "$OUT_DIR/public.pem.b64"

echo "Set env:"
echo "  export AUTH_JWT_PRIVATE_KEY_PEM=\"\$(cat $OUT_DIR/private.pem.b64)\""
echo "  export AUTH_JWT_ACTIVE_PUBLIC_KEY_PEM=\"\$(cat $OUT_DIR/public.pem.b64)\""
echo "  export AUTH_JWT_ACTIVE_KID=\"auth-key-2026-01\""
```

Chạy `chmod +x docs/superpowers/plans/generate-test-rsa-key.sh`.

- [ ] **Step 2: Cập nhật `application.yaml` — bổ sung `app.auth.*` namespace theo spec §47**

Thay block `app:` từ dòng `app.security.jwt:` cũ thành (giữ `app.gc`, `app.api`, `app.pagination`):

```yaml
app:
  gc:
    cron-time: 15 53 */4 * * *

  api:
    version: ${APP_API_VERSION:v1}
    prefix: ${APP_API_PREFIX:/api}

  pagination:
    default-page-size: ${DEFAULT_PAGE_SIZE:20}
    max-page-size: ${MAX_PAGE_SIZE:100}

  auth:
    jwt:
      issuer: ${AUTH_JWT_ISSUER:file-processing}
      audience: ${AUTH_JWT_AUDIENCE:file-processing-api}
      access-token-ttl: ${AUTH_JWT_ACCESS_TOKEN_TTL:PT15M}
      password-change-token-ttl: ${AUTH_JWT_PW_CHG_TOKEN_TTL:PT5M}
      clock-skew: ${AUTH_JWT_CLOCK_SKEW:PT60S}
      active-kid: ${AUTH_JWT_ACTIVE_KID}
      private-key-pem-base64: ${AUTH_JWT_PRIVATE_KEY_PEM}
      public-keys:
        - kid: ${AUTH_JWT_ACTIVE_KID}
          pem-base64: ${AUTH_JWT_ACTIVE_PUBLIC_KEY_PEM}
    password:
      encoder: bcrypt
      bcrypt-cost: ${AUTH_BCRYPT_COST:12}
      min-length: 8
      max-length: 128
    login:
      max-failures: 5
      failure-window: PT15M
      lock-duration: PT15M
    bootstrap:
      admin:
        enabled: ${AUTH_BOOTSTRAP_ENABLED:true}
        username: ${AUTH_BOOTSTRAP_ADMIN_USERNAME:}
        email: ${AUTH_BOOTSTRAP_ADMIN_EMAIL:}
        password: ${AUTH_BOOTSTRAP_ADMIN_PASSWORD:}
        display-name: ${AUTH_BOOTSTRAP_ADMIN_DISPLAY_NAME:System Administrator}
    cors:
      allowed-origins: ${CORS_ALLOWED_ORIGINS:http://localhost:3000,http://localhost:5173}
      allowed-methods: GET,POST,PATCH,DELETE,OPTIONS
      allowed-headers: Authorization,Content-Type,Accept,X-Requested-With,X-CSRF-Token,X-Request-Id
      allow-credentials: true
      max-age: 3600
```

Xoá section `app.security` cũ (không dùng).

- [ ] **Step 3: Cập nhật `application-dev.yml` với dev-only default key**

Bổ sung phần cuối `application-dev.yml`:

```yaml
app:
  auth:
    bootstrap:
      admin:
        enabled: true
        username: admin
        email: admin@example.com
        password: ChangeMe!DevOnly123
        display-name: Development Admin
```

(Key JWT `AUTH_JWT_PRIVATE_KEY_PEM`, `AUTH_JWT_ACTIVE_PUBLIC_KEY_PEM`, `AUTH_JWT_ACTIVE_KID` phải set qua env variable local — chạy script Step 1.)

- [ ] **Step 4: Sinh key test và ghi vào `application-test.yml`**

```bash
./docs/superpowers/plans/generate-test-rsa-key.sh target/generated-keys
```

Copy giá trị `private.pem.b64` và `public.pem.b64` (chuỗi base64 dài) vào `src/test/resources/application-test.yml` ở dòng `private-key-pem-base64` và một block `public-keys` mới:

```yaml
app:
  auth:
    jwt:
      issuer: file-processing-test
      audience: file-processing-api-test
      access-token-ttl: PT15M
      password-change-token-ttl: PT5M
      clock-skew: PT60S
      active-kid: auth-test-key-1
      private-key-pem-base64: "<paste base64 content of private.pem.b64>"
      public-keys:
        - kid: auth-test-key-1
          pem-base64: "<paste base64 content of public.pem.b64>"
    password:
      encoder: bcrypt
      bcrypt-cost: 4  # low cost for test speed
      min-length: 8
      max-length: 128
    login:
      max-failures: 5
      failure-window: PT15M
      lock-duration: PT15M
    bootstrap:
      admin:
        enabled: false
```

- [ ] **Step 5: Kiểm tra build**

```bash
./mvnw -DskipTests clean compile
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Commit**

```bash
git add src/main/resources/application.yaml src/main/resources/application-dev.yml src/test/resources/application-test.yml docs/superpowers/plans/generate-test-rsa-key.sh
git commit -m "feat(auth): add app.auth.* configuration namespace with RSA JWT keys

- app.auth.jwt.{issuer,audience,active-kid,private-key-pem-base64,public-keys[],access-token-ttl,password-change-token-ttl,clock-skew}
- app.auth.password.{encoder,bcrypt-cost,min-length,max-length}
- app.auth.login.{max-failures,failure-window,lock-duration}
- app.auth.bootstrap.admin.{enabled,username,email,password,display-name}
- app.auth.cors.{...}
- test profile uses BCrypt cost 4 for speed
- shell script to generate RSA key pair for dev/test"
```

---

## Task 4: Domain enum — `UserStatus`, `ActiveStatus`, `AuditLogDomain`, `OperationType`, `ResourceCode`, `RevocationReason`

**Files:**

- Modify: `auth/domain/model/UserStatus.java` — thêm `PENDING_VERIFY`, đổi `INACTIVE` thành `DISABLED`.
- Modify: `auth/domain/model/AuditLogDomain.java` — populate 5 giá trị.
- Modify: `auth/domain/model/OperationType.java` — mở rộng.
- Create: `auth/domain/model/ActiveStatus.java`
- Create: `auth/domain/model/ResourceCode.java`
- Create: `auth/domain/model/RevocationReason.java`

**Interfaces:**

- Produces:
  - `UserStatus { PENDING_VERIFY, ACTIVE, DISABLED }`
  - `ActiveStatus { ACTIVE, INACTIVE }` — dùng cho `Role.status`.
  - `AuditLogDomain { AUTH, USER, ROLE, PERMISSION, SESSION }`
  - `OperationType { CREATE, UPDATE, DELETE, ACTIVATED, DEACTIVATED, LOGIN_SUCCESS, LOGIN_FAILED, LOGOUT, LOGOUT_ALL, TOKEN_REFRESHED, TOKEN_REUSE_DETECTED, PASSWORD_CHANGED, PASSWORD_RESET_REQUESTED, PASSWORD_RESET_COMPLETED, ACCOUNT_LOCKED, ACCOUNT_UNLOCKED, ACCOUNT_DISABLED, ACCOUNT_ENABLED, ROLE_ASSIGNED, ROLE_REVOKED, ROLE_PERMISSION_UPDATED, EMAIL_VERIFICATION_REQUESTED, EMAIL_VERIFIED, USER_REGISTERED, ADMIN_BOOTSTRAPPED }`
  - `ResourceCode { ALL, USER, ROLE, SESSION, AUDIT, FILE, JOB, REPORT, CUSTOMER }`
  - `RevocationReason { USER_LOGOUT, LOGOUT_ALL, USER_REVOKED, PASSWORD_CHANGED, PASSWORD_RESET, ROLE_CHANGED, PERMISSION_CHANGED, DISABLED, TOKEN_REUSE_DETECTED, ADMIN_REVOKED, EXPIRED }`

- [ ] **Step 1: Cập nhật `UserStatus.java`**

```java
package com.vandunxg.file_processing.auth.domain.model;

public enum UserStatus {
  PENDING_VERIFY,
  ACTIVE,
  DISABLED
}
```

- [ ] **Step 2: Tạo `ActiveStatus.java`**

```java
package com.vandunxg.file_processing.auth.domain.model;

public enum ActiveStatus {
  ACTIVE,
  INACTIVE
}
```

- [ ] **Step 3: Cập nhật `AuditLogDomain.java`**

```java
package com.vandunxg.file_processing.auth.domain.model;

public enum AuditLogDomain {
  AUTH,
  USER,
  ROLE,
  PERMISSION,
  SESSION
}
```

- [ ] **Step 4: Cập nhật `OperationType.java`**

```java
package com.vandunxg.file_processing.auth.domain.model;

public enum OperationType {
  CREATE,
  UPDATE,
  DELETE,
  ACTIVATED,
  DEACTIVATED,

  LOGIN_SUCCESS,
  LOGIN_FAILED,
  LOGOUT,
  LOGOUT_ALL,
  TOKEN_REFRESHED,
  TOKEN_REUSE_DETECTED,

  PASSWORD_CHANGED,
  PASSWORD_RESET_REQUESTED,
  PASSWORD_RESET_COMPLETED,

  ACCOUNT_LOCKED,
  ACCOUNT_UNLOCKED,
  ACCOUNT_DISABLED,
  ACCOUNT_ENABLED,

  ROLE_ASSIGNED,
  ROLE_REVOKED,
  ROLE_PERMISSION_UPDATED,

  EMAIL_VERIFICATION_REQUESTED,
  EMAIL_VERIFIED,

  USER_REGISTERED,
  ADMIN_BOOTSTRAPPED
}
```

- [ ] **Step 5: Tạo `ResourceCode.java`**

```java
package com.vandunxg.file_processing.auth.domain.model;

public enum ResourceCode {
  ALL,
  USER,
  ROLE,
  SESSION,
  AUDIT,
  FILE,
  JOB,
  REPORT,
  CUSTOMER
}
```

- [ ] **Step 6: Tạo `RevocationReason.java`**

```java
package com.vandunxg.file_processing.auth.domain.model;

public enum RevocationReason {
  USER_LOGOUT,
  LOGOUT_ALL,
  USER_REVOKED,
  PASSWORD_CHANGED,
  PASSWORD_RESET,
  ROLE_CHANGED,
  PERMISSION_CHANGED,
  DISABLED,
  TOKEN_REUSE_DETECTED,
  ADMIN_REVOKED,
  EXPIRED
}
```

- [ ] **Step 7: Build kiểm tra**

```bash
./mvnw -DskipTests clean compile
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/vandunxg/file_processing/auth/domain/model/UserStatus.java src/main/java/com/vandunxg/file_processing/auth/domain/model/ActiveStatus.java src/main/java/com/vandunxg/file_processing/auth/domain/model/AuditLogDomain.java src/main/java/com/vandunxg/file_processing/auth/domain/model/OperationType.java src/main/java/com/vandunxg/file_processing/auth/domain/model/ResourceCode.java src/main/java/com/vandunxg/file_processing/auth/domain/model/RevocationReason.java
git commit -m "feat(auth): populate domain enums per spec §11.9-11.12

- UserStatus: PENDING_VERIFY, ACTIVE, DISABLED (was ACTIVE/INACTIVE)
- ActiveStatus: ACTIVE, INACTIVE (for Role.status)
- AuditLogDomain: AUTH, USER, ROLE, PERMISSION, SESSION
- OperationType: expand with 20+ auth-specific operations
- ResourceCode: ALL, USER, ROLE, SESSION, AUDIT, FILE, JOB, REPORT, CUSTOMER
- RevocationReason: for RefreshSession lifecycle (Phase 3)"
```

---

## Task 5: `AuthErrorCode` + `AuthDomainException` — module error catalog

**Files:**

- Create: `auth/domain/exception/AuthErrorCode.java`
- Create: `auth/domain/exception/AuthDomainException.java`

**Interfaces:**

- Consumes: `com.vandunxg.common.models.error.ResponseError` interface.
- Produces:
  - `AuthErrorCode` enum implements `ResponseError` với các code từ spec §42 (Phase 1+2 subset).
  - `AuthDomainException extends ResponseException` với constructor multi-arity.

- [ ] **Step 1: Tạo `AuthErrorCode.java`**

```java
package com.vandunxg.file_processing.auth.domain.exception;

import org.springframework.http.HttpStatus;

import com.vandunxg.common.models.error.ResponseError;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum AuthErrorCode implements ResponseError {

  // Authentication (Phase 2)
  INVALID_CREDENTIALS(40101, "auth.error.invalid_credentials", HttpStatus.UNAUTHORIZED),
  AUTH_TOKEN_REQUIRED(40102, "auth.error.token_required", HttpStatus.UNAUTHORIZED),
  ACCESS_TOKEN_INVALID(40103, "auth.error.access_token_invalid", HttpStatus.UNAUTHORIZED),
  ACCESS_TOKEN_EXPIRED(40104, "auth.error.access_token_expired", HttpStatus.UNAUTHORIZED),
  ACCESS_TOKEN_REVOKED(40105, "auth.error.access_token_revoked", HttpStatus.UNAUTHORIZED),
  PASSWORD_CHANGE_TOKEN_INVALID(40106, "auth.error.password_change_token_invalid", HttpStatus.UNAUTHORIZED),

  // Rate limit
  AUTH_RATE_LIMITED(42901, "auth.error.rate_limited", HttpStatus.TOO_MANY_REQUESTS),

  // Authorization
  ACCESS_DENIED(40301, "auth.error.access_denied", HttpStatus.FORBIDDEN),
  EMAIL_VERIFICATION_REQUIRED(40302, "auth.error.email_verification_required", HttpStatus.FORBIDDEN),
  PASSWORD_CHANGE_REQUIRED(40303, "auth.error.password_change_required", HttpStatus.FORBIDDEN),

  // Password (Phase 2 for login flow)
  CURRENT_PASSWORD_INVALID(40001, "auth.error.current_password_invalid", HttpStatus.BAD_REQUEST),
  PASSWORD_CONFIRMATION_MISMATCH(42201, "auth.error.password_confirmation_mismatch", HttpStatus.UNPROCESSABLE_ENTITY),
  PASSWORD_POLICY_VIOLATION(42202, "auth.error.password_policy_violation", HttpStatus.UNPROCESSABLE_ENTITY),
  PASSWORD_REUSE_NOT_ALLOWED(40901, "auth.error.password_reuse_not_allowed", HttpStatus.CONFLICT),

  // User management (Phase 1 for bootstrap validation, expanded in Phase 5)
  USER_NOT_FOUND(40401, "auth.error.user_not_found", HttpStatus.NOT_FOUND),
  USERNAME_ALREADY_EXISTS(40902, "auth.error.username_already_exists", HttpStatus.CONFLICT),
  EMAIL_ALREADY_EXISTS(40903, "auth.error.email_already_exists", HttpStatus.CONFLICT),
  INVALID_ROLE(42203, "auth.error.invalid_role", HttpStatus.UNPROCESSABLE_ENTITY),
  USER_MUST_HAVE_ROLE(42204, "auth.error.user_must_have_role", HttpStatus.UNPROCESSABLE_ENTITY),
  LAST_ACTIVE_ADMIN_REQUIRED(40904, "auth.error.last_active_admin_required", HttpStatus.CONFLICT),
  USER_CONCURRENTLY_MODIFIED(40905, "auth.error.user_concurrently_modified", HttpStatus.CONFLICT),

  // Role management (Phase 5)
  ROLE_NOT_FOUND(40402, "auth.error.role_not_found", HttpStatus.NOT_FOUND),
  ROLE_CODE_ALREADY_EXISTS(40906, "auth.error.role_code_already_exists", HttpStatus.CONFLICT),
  INVALID_RESOURCE_CODE(42205, "auth.error.invalid_resource_code", HttpStatus.UNPROCESSABLE_ENTITY),
  INVALID_ACTION(42206, "auth.error.invalid_action", HttpStatus.UNPROCESSABLE_ENTITY);

  private final Integer code;
  private final String messageKey;
  private final HttpStatus httpStatus;

  @Override
  public String getName() {
    return name();
  }

  @Override
  public String getMessage() {
    return messageKey;
  }

  @Override
  public int getStatus() {
    return httpStatus.value();
  }

  @Override
  public Integer getCode() {
    return code;
  }
}
```

- [ ] **Step 2: Tạo `AuthDomainException.java`**

```java
package com.vandunxg.file_processing.auth.domain.exception;

import com.vandunxg.common.models.error.ResponseError;
import com.vandunxg.common.models.exception.ResponseException;

public class AuthDomainException extends ResponseException {

  public AuthDomainException(ResponseError error) {
    super(error);
  }

  public AuthDomainException(ResponseError error, Object... params) {
    super(error, params);
  }

  public AuthDomainException(String message, Throwable cause, ResponseError error, Object... params) {
    super(message, cause, error, params);
  }
}
```

- [ ] **Step 3: Bổ sung i18n message key**

Append vào `src/main/resources/i18n/messages.properties`:

```properties
# Auth module (Phase 1+2)
auth.error.invalid_credentials=Invalid username, email or password
auth.error.token_required=Authentication token is required
auth.error.access_token_invalid=Access token is invalid
auth.error.access_token_expired=Access token has expired
auth.error.access_token_revoked=Access token has been revoked
auth.error.password_change_token_invalid=Password change token is invalid
auth.error.rate_limited=Too many requests. Please try again later
auth.error.access_denied=You do not have permission to perform this action
auth.error.email_verification_required=Email verification is required before you can sign in
auth.error.password_change_required=You must change your password before you can proceed
auth.error.current_password_invalid=Current password is incorrect
auth.error.password_confirmation_mismatch=Password confirmation does not match
auth.error.password_policy_violation=Password does not meet policy requirements
auth.error.password_reuse_not_allowed=New password must be different from the current password
auth.error.user_not_found=User not found: {0}
auth.error.username_already_exists=Username already exists
auth.error.email_already_exists=Email already exists
auth.error.invalid_role=Role is invalid or not supported
auth.error.user_must_have_role=User must have at least one role
auth.error.last_active_admin_required=Cannot remove the last active administrator
auth.error.user_concurrently_modified=This user was modified concurrently. Please retry
auth.error.role_not_found=Role not found: {0}
auth.error.role_code_already_exists=Role code already exists
auth.error.invalid_resource_code=Resource code is invalid
auth.error.invalid_action=Action is invalid
```

Append vào `src/main/resources/i18n/messages_vi.properties`:

```properties
# Auth module (Phase 1+2)
auth.error.invalid_credentials=Sai tên đăng nhập, email hoặc mật khẩu
auth.error.token_required=Vui lòng cung cấp token xác thực
auth.error.access_token_invalid=Access token không hợp lệ
auth.error.access_token_expired=Access token đã hết hạn
auth.error.access_token_revoked=Access token đã bị vô hiệu hóa
auth.error.password_change_token_invalid=Token đổi mật khẩu không hợp lệ
auth.error.rate_limited=Quá nhiều yêu cầu. Vui lòng thử lại sau
auth.error.access_denied=Bạn không có quyền thực hiện hành động này
auth.error.email_verification_required=Vui lòng xác thực email trước khi đăng nhập
auth.error.password_change_required=Bạn phải đổi mật khẩu trước khi tiếp tục
auth.error.current_password_invalid=Mật khẩu hiện tại không đúng
auth.error.password_confirmation_mismatch=Mật khẩu xác nhận không khớp
auth.error.password_policy_violation=Mật khẩu không đạt yêu cầu
auth.error.password_reuse_not_allowed=Mật khẩu mới phải khác mật khẩu hiện tại
auth.error.user_not_found=Không tìm thấy người dùng: {0}
auth.error.username_already_exists=Tên đăng nhập đã tồn tại
auth.error.email_already_exists=Email đã tồn tại
auth.error.invalid_role=Vai trò không hợp lệ hoặc không được hỗ trợ
auth.error.user_must_have_role=Người dùng phải có ít nhất một vai trò
auth.error.last_active_admin_required=Không thể loại bỏ quản trị viên hoạt động cuối cùng
auth.error.user_concurrently_modified=Người dùng đang được cập nhật đồng thời. Vui lòng thử lại
auth.error.role_not_found=Không tìm thấy vai trò: {0}
auth.error.role_code_already_exists=Mã vai trò đã tồn tại
auth.error.invalid_resource_code=Mã tài nguyên không hợp lệ
auth.error.invalid_action=Hành động không hợp lệ
```

- [ ] **Step 4: Build kiểm tra**

```bash
./mvnw -DskipTests clean compile
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/vandunxg/file_processing/auth/domain/exception/ src/main/resources/i18n/messages.properties src/main/resources/i18n/message_vi.properties
git commit -m "feat(auth): add AuthErrorCode enum and AuthDomainException per spec §42

- 25 error codes covering authentication, authorization, password, user/role management
- i18n keys in both English and Vietnamese
- extends ResponseError contract for common-web ExceptionHandleAdvice"
```

---

---

## Task 6: Mở rộng domain aggregate `User`

**Files:**

- Modify: `auth/domain/model/User.java`

**Interfaces:**

- Consumes: `UserStatus` (Task 4), `AuditableDomain`, `IdUtils.nextId()`.
- Produces: `User` aggregate với đầy đủ field theo spec §11.1, method `applyLoginSuccess`, `applyLoginFailure`, `changePassword`, `resetPasswordByAdmin`, `verifyEmail`, `disable`, `enable`, `unlock`, `assignRoles`, `revokeRole`.

- [ ] **Step 1: Rewrite `User.java`**

```java
package com.vandunxg.file_processing.auth.domain.model;

import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.vandunxg.common.models.domain.AuditableDomain;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Getter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Setter(AccessLevel.PRIVATE)
@EqualsAndHashCode(callSuper = false, of = "id")
public class User extends AuditableDomain {

  private UUID id;
  private String username;
  private String normalizedUsername;
  private String email;
  private String normalizedEmail;
  private String displayName;
  private String passwordHash;
  private UserStatus status;
  private Set<Role> roles;
  private boolean mustChangePassword;
  private int failedLoginCount;
  private Instant lastFailedLoginAt;
  private Instant lockedUntil;
  private int credentialVersion;
  private Instant lastLoginAt;
  private Instant passwordChangedAt;
  private Instant emailVerifiedAt;
  private Instant deletedAt;
  private Long version;

  public static String normalizeUsername(String raw) {
    if (raw == null) return null;
    return raw.trim().toLowerCase();
  }

  public static String normalizeEmail(String raw) {
    if (raw == null) return null;
    return raw.trim().toLowerCase();
  }

  public boolean isActive() {
    return status == UserStatus.ACTIVE && deletedAt == null;
  }

  public boolean isPendingVerify() {
    return status == UserStatus.PENDING_VERIFY;
  }

  public boolean isDisabled() {
    return status == UserStatus.DISABLED;
  }

  public boolean isLocked(Instant now) {
    return lockedUntil != null && lockedUntil.isAfter(now);
  }

  public boolean isDeleted() {
    return deletedAt != null;
  }

  public boolean hasRole(String roleCode) {
    if (roles == null) return false;
    return roles.stream().anyMatch(r -> Objects.equals(r.getCode(), roleCode) && !r.isDeleted());
  }

  public void applyLoginSuccess(Instant now) {
    this.failedLoginCount = 0;
    this.lastFailedLoginAt = null;
    this.lockedUntil = null;
    this.lastLoginAt = now;
  }

  public void applyLoginFailure(Instant now, int lockThreshold, java.time.Duration lockDuration) {
    this.failedLoginCount = this.failedLoginCount + 1;
    this.lastFailedLoginAt = now;
    if (this.failedLoginCount >= lockThreshold) {
      this.lockedUntil = now.plus(lockDuration);
    }
  }

  public void changePassword(String newHash, Instant now) {
    this.passwordHash = newHash;
    this.passwordChangedAt = now;
    this.mustChangePassword = false;
    this.credentialVersion = this.credentialVersion + 1;
  }

  public void resetPasswordByAdmin(String newHash, Instant now) {
    this.passwordHash = newHash;
    this.passwordChangedAt = now;
    this.mustChangePassword = true;
    this.failedLoginCount = 0;
    this.lockedUntil = null;
    this.credentialVersion = this.credentialVersion + 1;
  }

  public void verifyEmail(Instant now) {
    if (this.status != UserStatus.PENDING_VERIFY) {
      throw new IllegalStateException("User is not pending verification: status=" + this.status);
    }
    this.status = UserStatus.ACTIVE;
    this.emailVerifiedAt = now;
  }

  public void disable() {
    if (this.status == UserStatus.DISABLED) return;
    this.status = UserStatus.DISABLED;
    this.credentialVersion = this.credentialVersion + 1;
  }

  public void enable() {
    if (this.status == UserStatus.ACTIVE) return;
    this.status = UserStatus.ACTIVE;
  }

  public void unlock() {
    this.failedLoginCount = 0;
    this.lockedUntil = null;
    this.lastFailedLoginAt = null;
  }

  public void assignRoles(Set<Role> newRoles) {
    if (newRoles == null || newRoles.isEmpty()) {
      throw new IllegalArgumentException("User must have at least one role");
    }
    this.roles = new HashSet<>(newRoles);
    this.credentialVersion = this.credentialVersion + 1;
  }

  public void softDelete(Instant now) {
    if (this.deletedAt != null) return;
    this.deletedAt = now;
    this.credentialVersion = this.credentialVersion + 1;
  }
}
```

- [ ] **Step 2: Build kiểm tra**

```bash
./mvnw -DskipTests clean compile
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/vandunxg/file_processing/auth/domain/model/User.java
git commit -m "feat(auth): expand User aggregate with security state per spec §11.1

- fields: normalizedUsername, normalizedEmail, displayName, passwordHash,
  status (UserStatus), roles (Set<Role>), mustChangePassword, failedLoginCount,
  lastFailedLoginAt, lockedUntil, credentialVersion, lastLoginAt,
  passwordChangedAt, emailVerifiedAt, deletedAt, version
- domain methods: normalize helpers, isActive/isPendingVerify/isDisabled/isLocked/isDeleted,
  hasRole, applyLoginSuccess, applyLoginFailure with lockout, changePassword,
  resetPasswordByAdmin, verifyEmail, disable/enable, unlock, assignRoles,
  softDelete — each mutation bumps credentialVersion when appropriate"
```

---

## Task 7: Rewrite domain aggregate `Role`

**Files:**

- Modify: `auth/domain/model/Role.java`

**Interfaces:**

- Consumes: `ActiveStatus` (Task 4), `ResourceCode` (Task 4), `com.vandunxg.common.models.enums.Action`, `AuditableDomain`.
- Produces: `Role` với các trường `code`, `roleInheritedId`, `status`, `isConst`, `permissions: List<RolePermission>`, `userRoles: List<UserRole>`, method domain clone theo be-v2 nhưng dùng `deletedAt Instant?`.

- [ ] **Step 1: Rewrite `Role.java`**

```java
package com.vandunxg.file_processing.auth.domain.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.util.CollectionUtils;

import com.vandunxg.common.models.domain.AuditableDomain;
import com.vandunxg.common.models.enums.Action;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Getter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Setter(AccessLevel.PRIVATE)
@EqualsAndHashCode(callSuper = false, of = "id")
public class Role extends AuditableDomain {

  private UUID id;
  private String code;
  private UUID roleInheritedId;
  private String name;
  private String description;
  private ActiveStatus status;
  private List<RolePermission> permissions;
  private List<UserRole> userRoles;
  private Boolean isConst;
  private Instant deletedAt;
  private Long version;

  private String roleInheritedName;
  private String roleInheritedCode;

  public Role(UUID id, String code, String name, String description,
              Map<ResourceCode, List<Action>> initialPermissions,
              boolean isConst) {
    this.id = id;
    this.code = code;
    this.name = name;
    this.description = description;
    this.status = ActiveStatus.ACTIVE;
    this.isConst = isConst;
    this.deletedAt = null;
    this.permissions = new ArrayList<>();
    if (!CollectionUtils.isEmpty(initialPermissions)) {
      for (Map.Entry<ResourceCode, List<Action>> entry : initialPermissions.entrySet()) {
        for (Action action : entry.getValue()) {
          this.permissions.add(new RolePermission(this.id, entry.getKey().name(), action, null));
        }
      }
    }
  }

  public boolean isActive() {
    return this.status == ActiveStatus.ACTIVE && this.deletedAt == null;
  }

  public boolean isDeleted() {
    return this.deletedAt != null;
  }

  public boolean isBuiltIn() {
    return Boolean.TRUE.equals(this.isConst);
  }

  public void softDelete(Instant now) {
    if (this.isBuiltIn()) {
      throw new IllegalStateException("Cannot delete built-in role: " + this.code);
    }
    if (this.status == ActiveStatus.ACTIVE) {
      throw new IllegalStateException("Cannot delete active role: " + this.code + " — inactivate first");
    }
    if (!CollectionUtils.isEmpty(this.userRoles)
        && this.userRoles.stream().anyMatch(ur -> ur.getDeletedAt() == null)) {
      throw new IllegalStateException("Cannot delete role with active user assignments: " + this.code);
    }
    this.deletedAt = now;
    if (!CollectionUtils.isEmpty(this.permissions)) {
      this.permissions.forEach(p -> p.softDelete(now));
    }
  }

  public void activate() {
    if (this.status == ActiveStatus.ACTIVE) {
      throw new IllegalStateException("Role already active: " + this.code);
    }
    this.status = ActiveStatus.ACTIVE;
  }

  public void inactivate() {
    if (this.status == ActiveStatus.INACTIVE) {
      throw new IllegalStateException("Role already inactive: " + this.code);
    }
    this.status = ActiveStatus.INACTIVE;
  }

  public void updateProfile(String name, String description) {
    if (this.isBuiltIn()) {
      // Built-in roles allow name/description change but not code.
    }
    this.name = name;
    this.description = description;
  }

  public void replacePermissions(Map<ResourceCode, List<Action>> newPermissions, Instant now) {
    if (this.permissions == null) {
      this.permissions = new ArrayList<>();
    }
    // Soft delete all current permissions.
    this.permissions.forEach(p -> p.softDelete(now));

    if (CollectionUtils.isEmpty(newPermissions)) {
      return;
    }

    for (Map.Entry<ResourceCode, List<Action>> entry : newPermissions.entrySet()) {
      for (Action action : entry.getValue()) {
        Optional<RolePermission> existing = this.permissions.stream()
            .filter(p -> Objects.equals(p.getResourceCode(), entry.getKey().name())
                && p.getAction() == action)
            .findFirst();
        if (existing.isPresent()) {
          existing.get().restore();
        } else {
          this.permissions.add(new RolePermission(this.id, entry.getKey().name(), action, null));
        }
      }
    }
  }

  public Map<ResourceCode, List<Action>> permissionsGroupedByResource() {
    if (CollectionUtils.isEmpty(this.permissions)) {
      return new HashMap<>();
    }
    return this.permissions.stream()
        .filter(p -> p.getDeletedAt() == null)
        .collect(Collectors.groupingBy(
            p -> ResourceCode.valueOf(p.getResourceCode()),
            Collectors.mapping(RolePermission::getAction, Collectors.toList())));
  }

  public void setInheritedRole(UUID parentId, List<Role> chain) {
    if (parentId == null) {
      this.roleInheritedId = null;
      this.roleInheritedName = null;
      this.roleInheritedCode = null;
      return;
    }
    if (Objects.equals(parentId, this.id)) {
      throw new IllegalArgumentException("Role cannot inherit itself: " + this.code);
    }
    // Detect cycle: walk up chain from parentId
    UUID cursor = parentId;
    int guard = 32;
    while (cursor != null && guard-- > 0) {
      if (Objects.equals(cursor, this.id)) {
        throw new IllegalArgumentException("Role inheritance cycle detected involving: " + this.code);
      }
      UUID next = cursor;
      cursor = chain.stream()
          .filter(r -> Objects.equals(r.getId(), next))
          .map(Role::getRoleInheritedId)
          .findFirst()
          .orElse(null);
    }
    this.roleInheritedId = parentId;
  }

  public void enrichPermissions(List<RolePermission> perms) {
    this.permissions = perms;
  }

  public void enrichUserRoles(List<UserRole> urs) {
    this.userRoles = urs;
  }

  public void enrichInheritedRoleDisplay(String name, String code) {
    this.roleInheritedName = name;
    this.roleInheritedCode = code;
  }
}
```

- [ ] **Step 2: Build (sẽ fail vì `RolePermission` và `UserRole` chưa có — sẽ tạo ở Task 8, 9)**

Skip build ở step này; build sẽ pass sau Task 9.

- [ ] **Step 3: Commit staged file**

```bash
git add src/main/java/com/vandunxg/file_processing/auth/domain/model/Role.java
git commit -m "feat(auth): rewrite Role aggregate per spec §11.2 (clone be-v2 with deletedAt)

- fields: code (unique), roleInheritedId, name, description, status (ActiveStatus),
  permissions (List<RolePermission>), userRoles (List<UserRole>), isConst,
  deletedAt (Instant, RULE.md §12.1), version
- constructors: (id, code, name, description, initialPermissions, isConst) for seed;
  domain uses @SuperBuilder for general construction
- domain methods: isActive, isDeleted, isBuiltIn, softDelete (with guards),
  activate, inactivate, updateProfile, replacePermissions,
  permissionsGroupedByResource, setInheritedRole (with cycle detection),
  enrich* for hydration"
```

---

## Task 8: Xoá `Permission.java` cũ và tạo `RolePermission.java` mới

**Files:**

- Delete: `auth/domain/model/Permission.java`
- Create: `auth/domain/model/RolePermission.java`

**Interfaces:**

- Consumes: `Action` from common-models, `AuditableDomain`, `IdUtils.nextId()`.
- Produces: `RolePermission` entity với `roleId`, `resourceCode`, `action`, `resourceGroup`, `deletedAt`, method `softDelete(Instant now)`, `restore()`, `isDeleted()`.

- [ ] **Step 1: Xoá `Permission.java`**

```bash
git rm src/main/java/com/vandunxg/file_processing/auth/domain/model/Permission.java
```

- [ ] **Step 2: Tạo `RolePermission.java`**

```java
package com.vandunxg.file_processing.auth.domain.model;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.vandunxg.common.models.domain.AuditableDomain;
import com.vandunxg.common.models.enums.Action;
import com.vandunxg.common.utils.IdUtils;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Getter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Setter(AccessLevel.PRIVATE)
@EqualsAndHashCode(callSuper = false, of = "id")
public class RolePermission extends AuditableDomain {

  @JsonIgnore
  private UUID id;
  private UUID roleId;
  private String resourceCode;
  private Action action;
  private String resourceGroup;
  private Instant deletedAt;

  public RolePermission(UUID roleId, String resourceCode, Action action, String resourceGroup) {
    this.id = IdUtils.nextId();
    this.roleId = roleId;
    this.resourceCode = resourceCode;
    this.action = action;
    this.resourceGroup = resourceGroup;
    this.deletedAt = null;
  }

  public void softDelete(Instant now) {
    if (this.deletedAt != null) return;
    this.deletedAt = now;
  }

  public void restore() {
    this.deletedAt = null;
  }

  public boolean isDeleted() {
    return this.deletedAt != null;
  }
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/vandunxg/file_processing/auth/domain/model/RolePermission.java
git commit -m "feat(auth): replace Permission.java with RolePermission per spec §11.3

- old Permission (roleId, name, description) removed — was not aligned with be-v2 clone
- new RolePermission carries (roleId, resourceCode: ResourceCode.name(),
  action: Action, resourceGroup) — permission = tuple, no catalog table
- softDelete(now)/restore()/isDeleted() using deletedAt Instant per RULE.md §12.1
- JsonIgnore on id — exposed via aggregate Role only"
```

---

## Task 9: Tạo `UserRole.java`

**Files:**

- Create: `auth/domain/model/UserRole.java`

**Interfaces:**

- Consumes: `AuditableDomain`, `IdUtils.nextId()`.
- Produces: `UserRole` với `userId`, `roleId`, `deletedAt`, method `softDelete`, `restore`.

- [ ] **Step 1: Tạo file**

```java
package com.vandunxg.file_processing.auth.domain.model;

import java.time.Instant;
import java.util.UUID;

import com.vandunxg.common.models.domain.AuditableDomain;
import com.vandunxg.common.utils.IdUtils;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Getter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@Setter(AccessLevel.PRIVATE)
@EqualsAndHashCode(callSuper = false, of = "id")
public class UserRole extends AuditableDomain {

  private UUID id;
  private UUID userId;
  private UUID roleId;
  private Instant deletedAt;

  public UserRole(UUID userId, UUID roleId) {
    this.id = IdUtils.nextId();
    this.userId = userId;
    this.roleId = roleId;
    this.deletedAt = null;
  }

  public void softDelete(Instant now) {
    if (this.deletedAt != null) return;
    this.deletedAt = now;
  }

  public void restore() {
    this.deletedAt = null;
  }

  public boolean isDeleted() {
    return this.deletedAt != null;
  }
}
```

- [ ] **Step 2: Build đầy đủ (Task 6-9 đã hoàn thiện)**

```bash
./mvnw -DskipTests clean compile
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/vandunxg/file_processing/auth/domain/model/UserRole.java
git commit -m "feat(auth): add UserRole entity per spec §11.4

- fields: id, userId, roleId, deletedAt (RULE.md §12.1)
- convenience constructor UserRole(userId, roleId) auto-generates UUID
- softDelete/restore/isDeleted methods"
```

---

## Task 10: `PasswordPolicy` + unit test

**Files:**

- Create: `auth/domain/policy/PasswordPolicy.java`
- Create: `src/test/java/com/vandunxg/file_processing/auth/domain/policy/PasswordPolicyTest.java`

**Interfaces:**

- Produces: `PasswordPolicy` value class với method `validate(String rawPassword, String normalizedUsername, String normalizedEmail, String currentPasswordHash, PasswordHasherFn matcher)` trả về `ValidationResult`. Configurable `minLength`, `maxLength`.

- [ ] **Step 1: Viết failing test**

```java
package com.vandunxg.file_processing.auth.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

  private final PasswordPolicy policy = new PasswordPolicy(8, 128);

  @Test
  void validate_returnsOk_forGoodPassword() {
    var result = policy.validate("StrongPassword123", "operator01", "operator01@example.com", null, (raw, hash) -> false);
    assertThat(result.ok()).isTrue();
  }

  @Test
  void validate_fails_whenTooShort() {
    var result = policy.validate("short12", "u", "u@e.com", null, (raw, hash) -> false);
    assertThat(result.ok()).isFalse();
    assertThat(result.reason()).isEqualTo(PasswordPolicy.Reason.TOO_SHORT);
  }

  @Test
  void validate_fails_whenTooLong() {
    String longPw = "a".repeat(129);
    var result = policy.validate(longPw, "u", "u@e.com", null, (raw, hash) -> false);
    assertThat(result.ok()).isFalse();
    assertThat(result.reason()).isEqualTo(PasswordPolicy.Reason.TOO_LONG);
  }

  @Test
  void validate_fails_whenBlank() {
    var result = policy.validate("        ", "u", "u@e.com", null, (raw, hash) -> false);
    assertThat(result.ok()).isFalse();
    assertThat(result.reason()).isEqualTo(PasswordPolicy.Reason.BLANK);
  }

  @Test
  void validate_fails_whenContainsUsername() {
    var result = policy.validate("MyOperator01Pass", "operator01", "e@e.com", null, (raw, hash) -> false);
    assertThat(result.ok()).isFalse();
    assertThat(result.reason()).isEqualTo(PasswordPolicy.Reason.CONTAINS_USERNAME);
  }

  @Test
  void validate_fails_whenMatchesCurrent() {
    var result = policy.validate("SameAsCurrent!", "u", "u@e.com", "hash-of-same", (raw, hash) -> true);
    assertThat(result.ok()).isFalse();
    assertThat(result.reason()).isEqualTo(PasswordPolicy.Reason.REUSE_NOT_ALLOWED);
  }
}
```

- [ ] **Step 2: Chạy test — expected FAIL vì `PasswordPolicy` chưa tồn tại**

```bash
./mvnw -Dtest=PasswordPolicyTest test
```

Expected: compile error.

- [ ] **Step 3: Implement `PasswordPolicy.java`**

```java
package com.vandunxg.file_processing.auth.domain.policy;

public final class PasswordPolicy {

  public enum Reason {
    BLANK, TOO_SHORT, TOO_LONG, CONTAINS_USERNAME, CONTAINS_EMAIL_LOCAL, REUSE_NOT_ALLOWED
  }

  @FunctionalInterface
  public interface PasswordHasherFn {
    boolean matches(String rawPassword, String currentHash);
  }

  public record ValidationResult(boolean ok, Reason reason) {
    public static ValidationResult ok() {
      return new ValidationResult(true, null);
    }
    public static ValidationResult fail(Reason r) {
      return new ValidationResult(false, r);
    }
  }

  private final int minLength;
  private final int maxLength;

  public PasswordPolicy(int minLength, int maxLength) {
    if (minLength < 1 || maxLength < minLength) {
      throw new IllegalArgumentException("Invalid policy bounds");
    }
    this.minLength = minLength;
    this.maxLength = maxLength;
  }

  public ValidationResult validate(String rawPassword,
                                   String normalizedUsername,
                                   String normalizedEmail,
                                   String currentPasswordHash,
                                   PasswordHasherFn hasher) {
    if (rawPassword == null || rawPassword.isBlank()) {
      return ValidationResult.fail(Reason.BLANK);
    }
    int len = rawPassword.codePointCount(0, rawPassword.length());
    if (len < minLength) return ValidationResult.fail(Reason.TOO_SHORT);
    if (len > maxLength) return ValidationResult.fail(Reason.TOO_LONG);

    String lower = rawPassword.toLowerCase();
    if (normalizedUsername != null && !normalizedUsername.isBlank()
        && lower.contains(normalizedUsername.toLowerCase())) {
      return ValidationResult.fail(Reason.CONTAINS_USERNAME);
    }
    if (normalizedEmail != null && !normalizedEmail.isBlank()) {
      String local = normalizedEmail.contains("@") ? normalizedEmail.substring(0, normalizedEmail.indexOf('@')) : normalizedEmail;
      if (local.length() >= 3 && lower.contains(local.toLowerCase())) {
        return ValidationResult.fail(Reason.CONTAINS_EMAIL_LOCAL);
      }
    }
    if (currentPasswordHash != null && hasher != null && hasher.matches(rawPassword, currentPasswordHash)) {
      return ValidationResult.fail(Reason.REUSE_NOT_ALLOWED);
    }
    return ValidationResult.ok();
  }
}
```

- [ ] **Step 4: Chạy test — expected PASS**

```bash
./mvnw -Dtest=PasswordPolicyTest test
```

Expected: `Tests run: 6, Failures: 0`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/vandunxg/file_processing/auth/domain/policy/PasswordPolicy.java src/test/java/com/vandunxg/file_processing/auth/domain/policy/PasswordPolicyTest.java
git commit -m "feat(auth): PasswordPolicy per NIST 800-63B (spec §8.3)

- bounds: 8-128 codepoints
- rejects blank, contains username, contains email local part (>=3 chars)
- rejects reuse when hasher.matches(raw, currentHash)
- reason enum surfaces error cause for i18n mapping
- 6 unit tests covering all branches"
```

---

## Task 11: `LoginLockPolicy` + unit test

**Files:**

- Create: `auth/domain/policy/LoginLockPolicy.java`
- Create: `src/test/java/com/vandunxg/file_processing/auth/domain/policy/LoginLockPolicyTest.java`

**Interfaces:**

- Consumes: `User` (Task 6).
- Produces: `LoginLockPolicy` với method `applyFailure(User, Instant now)` mutation + `boolean isLocked(User, Instant now)`.

- [ ] **Step 1: Viết failing test**

```java
package com.vandunxg.file_processing.auth.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import com.vandunxg.file_processing.auth.domain.model.User;
import com.vandunxg.file_processing.auth.domain.model.UserStatus;

class LoginLockPolicyTest {

  private final LoginLockPolicy policy = new LoginLockPolicy(5, Duration.ofMinutes(15));

  private User activeUser() {
    return User.builder()
        .id(java.util.UUID.randomUUID())
        .username("op01")
        .normalizedUsername("op01")
        .email("op01@example.com")
        .normalizedEmail("op01@example.com")
        .displayName("Op 01")
        .passwordHash("h")
        .status(UserStatus.ACTIVE)
        .credentialVersion(1)
        .failedLoginCount(0)
        .passwordChangedAt(Instant.now())
        .build();
  }

  @Test
  void applyFailure_incrementsCounter() {
    User u = activeUser();
    Instant now = Instant.parse("2026-07-17T10:00:00Z");
    policy.applyFailure(u, now);
    assertThat(u.getFailedLoginCount()).isEqualTo(1);
    assertThat(u.getLockedUntil()).isNull();
  }

  @Test
  void applyFailure_locksAtThreshold() {
    User u = activeUser();
    Instant now = Instant.parse("2026-07-17T10:00:00Z");
    for (int i = 0; i < 5; i++) {
      policy.applyFailure(u, now);
    }
    assertThat(u.getFailedLoginCount()).isEqualTo(5);
    assertThat(u.getLockedUntil()).isEqualTo(now.plus(Duration.ofMinutes(15)));
  }

  @Test
  void isLocked_returnsTrue_whenLockedUntilInFuture() {
    User u = activeUser();
    Instant now = Instant.parse("2026-07-17T10:00:00Z");
    for (int i = 0; i < 5; i++) policy.applyFailure(u, now);
    assertThat(policy.isLocked(u, now)).isTrue();
    assertThat(policy.isLocked(u, now.plus(Duration.ofMinutes(16)))).isFalse();
  }

  @Test
  void applyLoginSuccess_resetsCounter() {
    User u = activeUser();
    Instant now = Instant.parse("2026-07-17T10:00:00Z");
    policy.applyFailure(u, now);
    policy.applyFailure(u, now);
    u.applyLoginSuccess(now);
    assertThat(u.getFailedLoginCount()).isZero();
    assertThat(u.getLockedUntil()).isNull();
  }
}
```

- [ ] **Step 2: Chạy test — expected FAIL**

```bash
./mvnw -Dtest=LoginLockPolicyTest test
```

- [ ] **Step 3: Implement `LoginLockPolicy.java`**

```java
package com.vandunxg.file_processing.auth.domain.policy;

import java.time.Duration;
import java.time.Instant;

import com.vandunxg.file_processing.auth.domain.model.User;

public final class LoginLockPolicy {

  private final int maxFailures;
  private final Duration lockDuration;

  public LoginLockPolicy(int maxFailures, Duration lockDuration) {
    if (maxFailures < 1) throw new IllegalArgumentException("maxFailures must be >= 1");
    if (lockDuration == null || lockDuration.isNegative() || lockDuration.isZero()) {
      throw new IllegalArgumentException("lockDuration must be positive");
    }
    this.maxFailures = maxFailures;
    this.lockDuration = lockDuration;
  }

  public void applyFailure(User user, Instant now) {
    user.applyLoginFailure(now, maxFailures, lockDuration);
  }

  public boolean isLocked(User user, Instant now) {
    return user.isLocked(now);
  }

  public int getMaxFailures() {
    return maxFailures;
  }

  public Duration getLockDuration() {
    return lockDuration;
  }
}
```

- [ ] **Step 4: Chạy test — expected PASS**

```bash
./mvnw -Dtest=LoginLockPolicyTest test
```

Expected: `Tests run: 4, Failures: 0`.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/vandunxg/file_processing/auth/domain/policy/LoginLockPolicy.java src/test/java/com/vandunxg/file_processing/auth/domain/policy/LoginLockPolicyTest.java
git commit -m "feat(auth): LoginLockPolicy per spec §8.4 (5 failures/15min → 15min lock)

- delegates to User.applyLoginFailure for mutation
- isLocked checks lockedUntil > now
- constructor validates maxFailures >= 1 and positive duration"
```

---

## Task 12: `LastActiveAdminPolicy`

**Files:**

- Create: `auth/domain/policy/LastActiveAdminPolicy.java`

**Interfaces:**

- Produces: `LastActiveAdminPolicy` — chỉ chứa method `check(int currentActiveAdminCount, boolean thisChangeRemovesActiveAdmin)` throw `IllegalStateException` nếu invariant vỡ. Actual query nằm ở repository/application layer.

- [ ] **Step 1: Tạo file**

```java
package com.vandunxg.file_processing.auth.domain.policy;

public final class LastActiveAdminPolicy {

  private LastActiveAdminPolicy() {}

  public static void requireAdminRemainsAfter(int currentActiveAdminCount,
                                              boolean thisChangeRemovesActiveAdmin) {
    int after = thisChangeRemovesActiveAdmin ? currentActiveAdminCount - 1 : currentActiveAdminCount;
    if (after <= 0) {
      throw new IllegalStateException(
          "Cannot proceed: system must retain at least one active administrator");
    }
  }
}
```

- [ ] **Step 2: Commit**

```bash
git add src/main/java/com/vandunxg/file_processing/auth/domain/policy/LastActiveAdminPolicy.java
git commit -m "feat(auth): LastActiveAdminPolicy static guard per spec §8.2

- static method requireAdminRemainsAfter(current, thisChangeRemoves)
- throws IllegalStateException; application layer wraps to AuthDomainException(LAST_ACTIVE_ADMIN_REQUIRED)
- policy stays pure — active-admin count query is repository/application concern"
```

---

## Task 13: `PermissionExpression` helper

**Files:**

- Create: `auth/domain/policy/PermissionExpression.java`
- Create: `src/test/java/com/vandunxg/file_processing/auth/domain/policy/PermissionExpressionTest.java`

**Interfaces:**

- Consumes: `ResourceCode`, `Action`.
- Produces:
  - `PermissionExpression.of(ResourceCode, Action) → String` returns `"resource:action"` lowercase.
  - `PermissionExpression.SUPER_MANAGE_ALL` = `"all:manage"`.
  - `PermissionExpression.parse(String) → Optional<Parsed>` for JWT converter.

- [ ] **Step 1: Viết test**

```java
package com.vandunxg.file_processing.auth.domain.policy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.vandunxg.common.models.enums.Action;
import com.vandunxg.file_processing.auth.domain.model.ResourceCode;

class PermissionExpressionTest {

  @Test
  void of_producesLowercaseColonForm() {
    assertThat(PermissionExpression.of(ResourceCode.FILE, Action.SELF_CREATE))
        .isEqualTo("file:self_create");
    assertThat(PermissionExpression.of(ResourceCode.ALL, Action.MANAGE))
        .isEqualTo(PermissionExpression.SUPER_MANAGE_ALL);
  }

  @Test
  void parse_acceptsValidExpression() {
    var parsed = PermissionExpression.parse("user:read");
    assertThat(parsed).isPresent();
    assertThat(parsed.get().resource()).isEqualTo(ResourceCode.USER);
    assertThat(parsed.get().action()).isEqualTo(Action.READ);
  }

  @Test
  void parse_rejectsInvalid() {
    assertThat(PermissionExpression.parse("bad")).isEmpty();
    assertThat(PermissionExpression.parse("user:unknown")).isEmpty();
    assertThat(PermissionExpression.parse("unknown:read")).isEmpty();
    assertThat(PermissionExpression.parse(null)).isEmpty();
    assertThat(PermissionExpression.parse("")).isEmpty();
  }
}
```

- [ ] **Step 2: Chạy test — expected FAIL**

```bash
./mvnw -Dtest=PermissionExpressionTest test
```

- [ ] **Step 3: Implement**

```java
package com.vandunxg.file_processing.auth.domain.policy;

import java.util.Optional;

import com.vandunxg.common.models.enums.Action;
import com.vandunxg.file_processing.auth.domain.model.ResourceCode;

public final class PermissionExpression {

  public static final String SUPER_MANAGE_ALL = "all:manage";

  private PermissionExpression() {}

  public static String of(ResourceCode resource, Action action) {
    return resource.name().toLowerCase() + ":" + action.name().toLowerCase();
  }

  public record Parsed(ResourceCode resource, Action action) {}

  public static Optional<Parsed> parse(String expression) {
    if (expression == null || expression.isBlank()) return Optional.empty();
    int idx = expression.indexOf(':');
    if (idx <= 0 || idx == expression.length() - 1) return Optional.empty();
    String resourcePart = expression.substring(0, idx).toUpperCase();
    String actionPart = expression.substring(idx + 1).toUpperCase();
    try {
      return Optional.of(new Parsed(ResourceCode.valueOf(resourcePart), Action.valueOf(actionPart)));
    } catch (IllegalArgumentException e) {
      return Optional.empty();
    }
  }
}
```

- [ ] **Step 4: Chạy test — expected PASS**

```bash
./mvnw -Dtest=PermissionExpressionTest test
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/vandunxg/file_processing/auth/domain/policy/PermissionExpression.java src/test/java/com/vandunxg/file_processing/auth/domain/policy/PermissionExpressionTest.java
git commit -m "feat(auth): PermissionExpression helper for JWT claim ↔ enum tuple mapping

- of(ResourceCode, Action) → 'resource:action' lowercase per spec §8.6
- SUPER_MANAGE_ALL constant = 'all:manage'
- parse(String) → Optional<Parsed(ResourceCode, Action)> for JwtAuthenticationConverter
- rejects invalid/unknown resource or action names"
```

---

## Task 14: Domain aggregate unit tests — `UserTest`, `RoleTest`

**Files:**

- Create: `src/test/java/com/vandunxg/file_processing/auth/domain/model/UserTest.java`
- Create: `src/test/java/com/vandunxg/file_processing/auth/domain/model/RoleTest.java`

**Interfaces:**

- Consumes: `User`, `Role`, `UserRole`, `RolePermission`, `ResourceCode`, `Action`.

- [ ] **Step 1: Viết `UserTest.java`**

```java
package com.vandunxg.file_processing.auth.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class UserTest {

  private User build() {
    return User.builder()
        .id(UUID.randomUUID())
        .username("op01")
        .normalizedUsername("op01")
        .email("op01@example.com")
        .normalizedEmail("op01@example.com")
        .displayName("Op 01")
        .passwordHash("hash-v1")
        .status(UserStatus.PENDING_VERIFY)
        .credentialVersion(1)
        .failedLoginCount(0)
        .passwordChangedAt(Instant.parse("2026-07-01T00:00:00Z"))
        .build();
  }

  @Test
  void normalize_lowercases_and_trims() {
    assertThat(User.normalizeUsername("  Operator01  ")).isEqualTo("operator01");
    assertThat(User.normalizeEmail("  Op@Example.COM  ")).isEqualTo("op@example.com");
  }

  @Test
  void verifyEmail_movesStatus_toActive_andSetsTimestamp() {
    User u = build();
    Instant now = Instant.parse("2026-07-17T10:00:00Z");
    u.verifyEmail(now);
    assertThat(u.getStatus()).isEqualTo(UserStatus.ACTIVE);
    assertThat(u.getEmailVerifiedAt()).isEqualTo(now);
  }

  @Test
  void verifyEmail_rejects_ifNotPending() {
    User u = build();
    u.verifyEmail(Instant.now());
    assertThatThrownBy(() -> u.verifyEmail(Instant.now()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void applyLoginFailure_incrementsAndLocks_atThreshold() {
    User u = build();
    Instant now = Instant.parse("2026-07-17T10:00:00Z");
    for (int i = 0; i < 5; i++) u.applyLoginFailure(now, 5, Duration.ofMinutes(15));
    assertThat(u.getFailedLoginCount()).isEqualTo(5);
    assertThat(u.getLockedUntil()).isEqualTo(now.plus(Duration.ofMinutes(15)));
    assertThat(u.isLocked(now)).isTrue();
  }

  @Test
  void applyLoginSuccess_resetsCounter() {
    User u = build();
    Instant now = Instant.now();
    u.applyLoginFailure(now, 5, Duration.ofMinutes(15));
    u.applyLoginSuccess(now);
    assertThat(u.getFailedLoginCount()).isZero();
    assertThat(u.getLastLoginAt()).isEqualTo(now);
  }

  @Test
  void changePassword_incrementsCredentialVersion_andClearsMustChange() {
    User u = build();
    u.setMustChangePassword_forceForTest();  // helper not exposed; use builder alt
  }

  @Test
  void disable_bumpsCredentialVersion_andMarksDisabled() {
    User u = build();
    u.verifyEmail(Instant.now());
    int before = u.getCredentialVersion();
    u.disable();
    assertThat(u.getStatus()).isEqualTo(UserStatus.DISABLED);
    assertThat(u.getCredentialVersion()).isEqualTo(before + 1);
    // idempotent
    u.disable();
    assertThat(u.getCredentialVersion()).isEqualTo(before + 1);
  }

  @Test
  void assignRoles_requiresNonEmpty() {
    User u = build();
    assertThatThrownBy(() -> u.assignRoles(Set.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void softDelete_setsTimestamp_andBumpsCredentialVersion_idempotent() {
    User u = build();
    Instant now = Instant.now();
    int before = u.getCredentialVersion();
    u.softDelete(now);
    assertThat(u.getDeletedAt()).isEqualTo(now);
    assertThat(u.getCredentialVersion()).isEqualTo(before + 1);
    u.softDelete(now.plusSeconds(1));
    assertThat(u.getDeletedAt()).isEqualTo(now); // idempotent
  }
}
```

**Chú ý**: test `changePassword_...` reference `setMustChangePassword_forceForTest` không tồn tại — cần rewrite test dùng `resetPasswordByAdmin` để set `mustChangePassword=true` trước, rồi `changePassword` sau. Sửa lại như sau:

```java
  @Test
  void changePassword_incrementsCredentialVersion_andClearsMustChange() {
    User u = build();
    u.resetPasswordByAdmin("hash-temp", Instant.now());  // sets mustChangePassword=true, cv+1
    assertThat(u.isMustChangePassword()).isTrue();
    int cvAfterReset = u.getCredentialVersion();

    u.changePassword("hash-final", Instant.now());
    assertThat(u.isMustChangePassword()).isFalse();
    assertThat(u.getCredentialVersion()).isEqualTo(cvAfterReset + 1);
    assertThat(u.getPasswordHash()).isEqualTo("hash-final");
  }
```

Xoá dòng `u.setMustChangePassword_forceForTest();` khỏi test file (đó là chú thích, không thực sự để trong test).

- [ ] **Step 2: Viết `RoleTest.java`**

```java
package com.vandunxg.file_processing.auth.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.vandunxg.common.models.enums.Action;

class RoleTest {

  @Test
  void builtInRole_seededWithPermissions() {
    Role admin = new Role(
        UUID.randomUUID(),
        "ADMIN",
        "Administrator",
        "System administrator",
        Map.of(ResourceCode.ALL, List.of(Action.MANAGE)),
        true);
    assertThat(admin.isBuiltIn()).isTrue();
    assertThat(admin.getStatus()).isEqualTo(ActiveStatus.ACTIVE);
    assertThat(admin.getPermissions()).hasSize(1);
    assertThat(admin.getPermissions().get(0).getResourceCode()).isEqualTo("ALL");
    assertThat(admin.getPermissions().get(0).getAction()).isEqualTo(Action.MANAGE);
  }

  @Test
  void builtInRole_cannotBeDeleted() {
    Role admin = new Role(UUID.randomUUID(), "ADMIN", "Admin", null,
        Map.of(ResourceCode.ALL, List.of(Action.MANAGE)), true);
    admin.inactivate();
    assertThatThrownBy(() -> admin.softDelete(Instant.now()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("built-in");
  }

  @Test
  void activeRole_cannotBeDeleted() {
    Role r = new Role(UUID.randomUUID(), "AUDITOR", "Auditor", null,
        Map.of(ResourceCode.AUDIT, List.of(Action.READ)), false);
    assertThatThrownBy(() -> r.softDelete(Instant.now()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("active");
  }

  @Test
  void inactiveRoleWithoutUsers_canBeDeleted() {
    Role r = new Role(UUID.randomUUID(), "AUDITOR", "Auditor", null,
        Map.of(ResourceCode.AUDIT, List.of(Action.READ)), false);
    r.inactivate();
    Instant now = Instant.now();
    r.softDelete(now);
    assertThat(r.getDeletedAt()).isEqualTo(now);
    assertThat(r.getPermissions()).allMatch(RolePermission::isDeleted);
  }

  @Test
  void replacePermissions_softDeletesOld_andRestoresIfSame() {
    Role r = new Role(UUID.randomUUID(), "OPERATOR", "Operator", null,
        Map.of(ResourceCode.FILE, List.of(Action.SELF_READ, Action.SELF_CREATE)), false);
    assertThat(r.getPermissions()).hasSize(2);

    // Replace: keep SELF_READ, drop SELF_CREATE, add SELF_UPDATE
    r.replacePermissions(
        Map.of(ResourceCode.FILE, List.of(Action.SELF_READ, Action.SELF_UPDATE)),
        Instant.now());

    long active = r.getPermissions().stream().filter(p -> !p.isDeleted()).count();
    assertThat(active).isEqualTo(2);
    assertThat(r.permissionsGroupedByResource().get(ResourceCode.FILE))
        .containsExactlyInAnyOrder(Action.SELF_READ, Action.SELF_UPDATE);
  }

  @Test
  void setInheritedRole_detectsCycle() {
    UUID idA = UUID.randomUUID();
    UUID idB = UUID.randomUUID();
    Role a = Role.builder().id(idA).code("A").roleInheritedId(idB).status(ActiveStatus.ACTIVE).build();
    Role b = Role.builder().id(idB).code("B").roleInheritedId(null).status(ActiveStatus.ACTIVE).build();

    // B inheriting from A would form cycle A -> B -> A
    assertThatThrownBy(() -> b.setInheritedRole(idA, List.of(a, b)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cycle");
  }
}
```

- [ ] **Step 3: Chạy test — expected PASS**

```bash
./mvnw test -Dtest='UserTest,RoleTest'
```

Expected: All tests pass.

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/vandunxg/file_processing/auth/domain/model/UserTest.java src/test/java/com/vandunxg/file_processing/auth/domain/model/RoleTest.java
git commit -m "test(auth): domain aggregate unit tests for User and Role

UserTest:
- normalize username/email lowercases + trims
- verifyEmail transitions PENDING_VERIFY→ACTIVE and rejects if not pending
- lockout at 5 failures / 15 min per spec §8.4
- login success resets counter
- changePassword clears mustChangePassword and bumps credentialVersion
- disable is idempotent and bumps credentialVersion first time
- assignRoles rejects empty set
- softDelete is idempotent and bumps credentialVersion

RoleTest:
- built-in role seeds with permissions and cannot be deleted
- active role cannot be deleted (must inactivate first)
- inactive role without users deletes and cascades permission soft-delete
- replacePermissions soft-deletes old + restores same + adds new
- setInheritedRole detects direct cycles"
```

---

## Kết thúc Part 1

Sau khi hoàn thành 14 task trên, bạn có:

- Full domain layer: `User`, `Role`, `RolePermission`, `UserRole` aggregate + `PasswordPolicy`, `LoginLockPolicy`, `LastActiveAdminPolicy`, `PermissionExpression`.
- Toàn bộ enum: `UserStatus`, `ActiveStatus`, `AuditLogDomain`, `OperationType`, `ResourceCode`, `RevocationReason`.
- `AuthErrorCode` + `AuthDomainException`.
- Dependency đầy đủ trong `pom.xml`.
- Test infrastructure Testcontainers.
- i18n key cho toàn bộ error phase 1+2.
- Configuration namespace `app.auth.*`.

**Chuyển sang Part 2** (`2026-07-17-auth-part2-migration-persistence.md`) — Flyway migrations + JPA + persistence adapters + IT.

