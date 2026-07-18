# Auth Module — Part 2: Migration + Persistence + IT

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task.

> **Part 2 of 4** — Prerequisite: Part 1 (`2026-07-17-auth-part1-setup-domain.md`) đã hoàn thành. Có thể chạy `./mvnw test` với domain unit test pass.

**Goal:** Ship Flyway migrations tạo toàn bộ schema Auth Module (`auth_users`, `role`, `role_permission`, `user_role`, `audit_logs`) + seed `ADMIN`/`OPERATOR` role. Ship JPA entity + persistence mapper (MapStruct) + Spring Data repository + persistence adapter cho từng aggregate. Ship `PasswordHasherPort` + BCrypt adapter, `ClockPort`, `IdGeneratorPort`, `AuthProperties` typed config. Integration test qua Testcontainers PostgreSQL 16 chứng minh migration + seed + CRUD hoạt động.

**Architecture:** Hexagonal outbound adapter. Domain và JPA entity là **hai class khác nhau** (`RULE.md §5, §12`). Mapping qua MapStruct interface `@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)` implement `EntityMapper<D, E>` từ `common-models`. Persistence adapter (`<Xxx>PersistenceAdapter`) là bean `@Service`/`@Repository` implement outbound port từ application layer.

**Tech Stack:** Flyway (postgresql driver), JPA/Hibernate 6, MapStruct 1.6.3, PostgreSQL 16, BCrypt qua `DelegatingPasswordEncoder`, Testcontainers `org.testcontainers:postgresql`, `spring-boot-starter-data-jpa-test`.

## Global Constraints

Kế thừa toàn bộ Global Constraints của Part 1 (`RULE.md`, `AGENTS.md`, spec §). Bổ sung ràng buộc riêng cho Part 2:

- **Không** tạo unique constraint tự nhiên trên `(role_id, resource_code, action)` hay `(user_id, role_id)` — theo clone be-v2, duplicate được filter tại domain (`Role.replacePermissions`) và application service (spec §11.3, §11.4).
- Partial index `WHERE deleted_at IS NULL` cho mọi bảng có soft-delete (`RULE.md §12.1`).
- Migration filename `V202607170900__*.sql`, `V202607170901__*.sql`, ... — timestamp prefix cố định để tránh conflict giữa developer (spec §40).
- JPA entity extends `com.vandunxg.common.models.entities.AuditableEntity` để tự động điền `created_at`, `last_modified_at`, `created_by`, `last_modified_by` qua `@EnableJpaAuditing` + `SpringSecurityAuditorAware` (đã có trong `SecurityConfiguration.java`).
- Domain aggregate KHÔNG import `jakarta.persistence.*` (`RULE.md §5`).
- Repository adapter (`<Xxx>PersistenceAdapter`) `@Service` hoặc `@Repository`, constructor injection `@RequiredArgsConstructor`.
- BCrypt cost = giá trị `app.auth.password.bcrypt-cost` (dev/prod 12, test 4 cho tốc độ).

## File Structure

**Create (migration):**
- `src/main/resources/db/migration/V202607170900__create_auth_users.sql`
- `src/main/resources/db/migration/V202607170901__create_role.sql`
- `src/main/resources/db/migration/V202607170902__create_role_permission.sql`
- `src/main/resources/db/migration/V202607170903__create_user_role.sql`
- `src/main/resources/db/migration/V202607170904__create_audit_logs.sql`
- `src/main/resources/db/migration/V202607170905__seed_default_roles.sql`

**Create (JPA entity):**
- `auth/adapter/out/persistence/entity/UserEntity.java`
- `auth/adapter/out/persistence/entity/RoleEntity.java`
- `auth/adapter/out/persistence/entity/RolePermissionEntity.java`
- `auth/adapter/out/persistence/entity/UserRoleEntity.java`
- `auth/adapter/out/persistence/entity/AuditLogEntity.java`

**Modify / Rewrite (Spring Data repository):**
- `auth/adapter/out/persistence/entity/JpaUserRepository.java` (rewrite stub)
- Create `auth/adapter/out/persistence/entity/JpaRoleRepository.java`
- Create `auth/adapter/out/persistence/entity/JpaRolePermissionRepository.java`
- Create `auth/adapter/out/persistence/entity/JpaUserRoleRepository.java`
- Create `auth/adapter/out/persistence/entity/JpaAuditLogRepository.java`

**Create (MapStruct mapper):**
- `auth/adapter/out/persistence/mapper/UserPersistenceMapper.java`
- `auth/adapter/out/persistence/mapper/RolePersistenceMapper.java`
- `auth/adapter/out/persistence/mapper/RolePermissionPersistenceMapper.java`
- `auth/adapter/out/persistence/mapper/UserRolePersistenceMapper.java`
- `auth/adapter/out/persistence/mapper/AuditLogPersistenceMapper.java`

**Create (outbound port + adapter):**
- `auth/application/port/out/UserRepositoryPort.java` (rewrite stub — convert to interface)
- `auth/application/port/out/RoleRepositoryPort.java`
- `auth/application/port/out/RolePermissionRepositoryPort.java`
- `auth/application/port/out/UserRoleRepositoryPort.java`
- `auth/application/port/out/AuditLogPort.java`
- `auth/application/port/out/PasswordHasherPort.java`
- `auth/application/port/out/ClockPort.java`
- `auth/application/port/out/IdGeneratorPort.java`
- `auth/adapter/out/persistence/UserPersistenceAdapter.java`
- `auth/adapter/out/persistence/RolePersistenceAdapter.java`
- `auth/adapter/out/persistence/RolePermissionPersistenceAdapter.java`
- `auth/adapter/out/persistence/UserRolePersistenceAdapter.java`
- `auth/adapter/out/persistence/AuditLogPersistenceAdapter.java`
- `auth/adapter/out/password/BcryptPasswordHasherAdapter.java`
- `auth/adapter/out/system/SystemClockAdapter.java`
- `auth/adapter/out/system/UuidIdGeneratorAdapter.java`

**Create (configuration):**
- `auth/configuration/AuthProperties.java`
- `auth/configuration/AuthPersistenceConfiguration.java`

**Create (test):**
- `src/test/java/com/vandunxg/file_processing/auth/adapter/out/persistence/MigrationAndSeedIT.java`
- `src/test/java/com/vandunxg/file_processing/auth/adapter/out/persistence/UserPersistenceAdapterIT.java`
- `src/test/java/com/vandunxg/file_processing/auth/adapter/out/persistence/RolePersistenceAdapterIT.java`
- `src/test/java/com/vandunxg/file_processing/auth/adapter/out/persistence/LastActiveAdminQueryIT.java`

---

## Task 15: Migration V01 — `auth_users`

**Files:**
- Create: `src/main/resources/db/migration/V202607170900__create_auth_users.sql`

**Interfaces:**
- Produces: bảng `auth_users` với schema theo spec §40.1.

- [ ] **Step 1: Tạo migration file**

```sql
-- V202607170900__create_auth_users.sql
-- Bảng người dùng cho Auth Module (spec §40.1)
-- Soft delete theo convention RULE.md §12.1

CREATE TABLE auth_users (
    id                    UUID PRIMARY KEY,
    username              VARCHAR(64)  NOT NULL,
    normalized_username   VARCHAR(64)  NOT NULL,
    email                 VARCHAR(254) NOT NULL,
    normalized_email      VARCHAR(254) NOT NULL,
    display_name          VARCHAR(150) NOT NULL,
    password_hash         VARCHAR(255) NOT NULL,
    status                VARCHAR(20)  NOT NULL,
    must_change_password  BOOLEAN      NOT NULL DEFAULT FALSE,
    failed_login_count    INTEGER      NOT NULL DEFAULT 0,
    last_failed_login_at  TIMESTAMPTZ,
    locked_until          TIMESTAMPTZ,
    credential_version    INTEGER      NOT NULL DEFAULT 1,
    last_login_at         TIMESTAMPTZ,
    password_changed_at   TIMESTAMPTZ  NOT NULL,
    email_verified_at     TIMESTAMPTZ,
    deleted_at            TIMESTAMPTZ,
    created_by            VARCHAR(100),
    created_at            TIMESTAMPTZ  NOT NULL,
    last_modified_by      VARCHAR(100),
    last_modified_at      TIMESTAMPTZ  NOT NULL,
    version               BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT auth_users_status_chk
        CHECK (status IN ('PENDING_VERIFY', 'ACTIVE', 'DISABLED'))
);

-- Unique constraints scoped by soft-delete (partial unique index)
CREATE UNIQUE INDEX auth_users_normalized_username_uk
    ON auth_users (normalized_username)
    WHERE deleted_at IS NULL;

CREATE UNIQUE INDEX auth_users_normalized_email_uk
    ON auth_users (normalized_email)
    WHERE deleted_at IS NULL;

-- Query indexes
CREATE INDEX auth_users_status_created_at_idx
    ON auth_users (status, created_at DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX auth_users_locked_until_idx
    ON auth_users (locked_until)
    WHERE locked_until IS NOT NULL AND deleted_at IS NULL;

CREATE INDEX auth_users_deleted_at_idx
    ON auth_users (deleted_at)
    WHERE deleted_at IS NOT NULL;
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/db/migration/V202607170900__create_auth_users.sql
git commit -m "feat(auth): migration V01 create auth_users table

- schema per spec §40.1
- soft delete via deleted_at TIMESTAMPTZ per RULE.md §12.1
- partial unique index on normalized_username, normalized_email (scoped by deleted_at IS NULL)
  allows re-creating a user with same username after soft-delete
- check constraint on status: PENDING_VERIFY, ACTIVE, DISABLED
- version column for optimistic locking"
```

---

## Task 16: Migration V02 — `role`

**Files:**
- Create: `src/main/resources/db/migration/V202607170901__create_role.sql`

- [ ] **Step 1: Tạo file**

```sql
-- V202607170901__create_role.sql
-- Bảng vai trò (spec §40.2, clone be-v2 với deleted_at)

CREATE TABLE role (
    id                    UUID PRIMARY KEY,
    role_inherited_id     UUID,
    code                  VARCHAR(50)  NOT NULL,
    name                  VARCHAR(100) NOT NULL,
    description           VARCHAR(1000),
    is_const              BOOLEAN               DEFAULT FALSE,
    status                VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    deleted_at            TIMESTAMPTZ,
    version               BIGINT       NOT NULL DEFAULT 0,
    created_by            VARCHAR(100),
    created_at            TIMESTAMPTZ  NOT NULL,
    last_modified_by      VARCHAR(100),
    last_modified_at      TIMESTAMPTZ  NOT NULL,

    CONSTRAINT role_status_chk CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT role_inherited_fk FOREIGN KEY (role_inherited_id) REFERENCES role(id) ON DELETE SET NULL
);

CREATE UNIQUE INDEX role_code_uk
    ON role (code)
    WHERE deleted_at IS NULL;

CREATE INDEX role_active_status_idx
    ON role (status)
    WHERE deleted_at IS NULL;

CREATE INDEX role_deleted_at_idx
    ON role (deleted_at)
    WHERE deleted_at IS NOT NULL;
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/db/migration/V202607170901__create_role.sql
git commit -m "feat(auth): migration V02 create role table with self-FK for inheritance

- schema per spec §40.2 (clone be-v2, deleted_at per RULE.md §12.1)
- self-FK role_inherited_id ON DELETE SET NULL (soft-delete parent breaks inheritance safely)
- unique code partial index scoped by deleted_at IS NULL
- check constraint on status: ACTIVE, INACTIVE"
```

---

## Task 17: Migration V03 — `role_permission`

**Files:**
- Create: `src/main/resources/db/migration/V202607170902__create_role_permission.sql`

- [ ] **Step 1: Tạo file**

```sql
-- V202607170902__create_role_permission.sql
-- Bảng permission — entity tuple (role_id, resource_code, action) (spec §40.3)

CREATE TABLE role_permission (
    id                    UUID PRIMARY KEY,
    role_id               UUID         NOT NULL,
    resource_code         VARCHAR(50)  NOT NULL,
    action                VARCHAR(20)  NOT NULL,
    resource_group        VARCHAR(255),
    deleted_at            TIMESTAMPTZ,
    created_by            VARCHAR(100),
    created_at            TIMESTAMPTZ  NOT NULL,
    last_modified_by      VARCHAR(100),
    last_modified_at      TIMESTAMPTZ  NOT NULL,

    CONSTRAINT role_permission_role_fk
        FOREIGN KEY (role_id) REFERENCES role(id) ON DELETE CASCADE
);

CREATE INDEX role_permission_role_id_idx
    ON role_permission (role_id);

CREATE INDEX role_permission_active_idx
    ON role_permission (role_id, resource_code, action)
    WHERE deleted_at IS NULL;

CREATE INDEX role_permission_resource_active_idx
    ON role_permission (resource_code, action)
    WHERE deleted_at IS NULL;

CREATE INDEX role_permission_deleted_at_idx
    ON role_permission (deleted_at)
    WHERE deleted_at IS NOT NULL;
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/db/migration/V202607170902__create_role_permission.sql
git commit -m "feat(auth): migration V03 create role_permission entity table

- schema per spec §40.3 (be-v2 clone, deleted_at)
- FK role_id ON DELETE CASCADE — hard delete role removes permissions atomically
  (soft-delete via role.deleted_at handled at application layer)
- NO unique constraint on (role_id, resource_code, action) — dedup at domain
  Role.replacePermissions per be-v2 pattern
- partial index for active lookup (main permission resolution path)"
```

---

## Task 18: Migration V04 — `user_role`

**Files:**
- Create: `src/main/resources/db/migration/V202607170903__create_user_role.sql`

- [ ] **Step 1: Tạo file**

```sql
-- V202607170903__create_user_role.sql
-- Bảng gán role cho user (spec §40.4)

CREATE TABLE user_role (
    id                    UUID PRIMARY KEY,
    user_id               UUID         NOT NULL,
    role_id               UUID         NOT NULL,
    deleted_at            TIMESTAMPTZ,
    created_by            VARCHAR(100),
    created_at            TIMESTAMPTZ  NOT NULL,
    last_modified_by      VARCHAR(100),
    last_modified_at      TIMESTAMPTZ  NOT NULL,

    CONSTRAINT user_role_user_fk
        FOREIGN KEY (user_id) REFERENCES auth_users(id) ON DELETE CASCADE,
    CONSTRAINT user_role_role_fk
        FOREIGN KEY (role_id) REFERENCES role(id) ON DELETE CASCADE
);

CREATE INDEX user_role_active_user_idx
    ON user_role (user_id)
    WHERE deleted_at IS NULL;

CREATE INDEX user_role_active_role_idx
    ON user_role (role_id)
    WHERE deleted_at IS NULL;

CREATE INDEX user_role_deleted_at_idx
    ON user_role (deleted_at)
    WHERE deleted_at IS NOT NULL;
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/db/migration/V202607170903__create_user_role.sql
git commit -m "feat(auth): migration V04 create user_role assignment table

- schema per spec §40.4 (be-v2 clone, deleted_at)
- FK ON DELETE CASCADE (hard-delete user or role removes assignment)
- NO unique constraint on (user_id, role_id) — dedup at application layer
- partial indexes for user's active roles and role's active users
  (latter used to invalidate credentialVersion when role permissions change)"
```

---

## Task 19: Migration V05 — `audit_logs`

**Files:**
- Create: `src/main/resources/db/migration/V202607170904__create_audit_logs.sql`

- [ ] **Step 1: Tạo file**

```sql
-- V202607170904__create_audit_logs.sql
-- Bảng audit log dùng chung (spec §40.9)

CREATE TABLE audit_logs (
    id                    UUID PRIMARY KEY,
    domain                VARCHAR(50)  NOT NULL,
    object_id             UUID,
    operation             VARCHAR(50)  NOT NULL,
    changed_by            UUID,
    changed_at            TIMESTAMPTZ  NOT NULL,
    data                  JSONB,
    ip_address            VARCHAR(64),
    browser               VARCHAR(64),
    user_agent            VARCHAR(200),
    deleted_at            TIMESTAMPTZ,
    created_by            VARCHAR(100),
    created_at            TIMESTAMPTZ  NOT NULL,
    last_modified_by      VARCHAR(100),
    last_modified_at      TIMESTAMPTZ  NOT NULL
);

CREATE INDEX audit_logs_domain_op_time_idx
    ON audit_logs (domain, operation, changed_at DESC);

CREATE INDEX audit_logs_actor_time_idx
    ON audit_logs (changed_by, changed_at DESC)
    WHERE changed_by IS NOT NULL;

CREATE INDEX audit_logs_object_time_idx
    ON audit_logs (object_id, changed_at DESC)
    WHERE object_id IS NOT NULL;
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/db/migration/V202607170904__create_audit_logs.sql
git commit -m "feat(auth): migration V05 create audit_logs shared table

- schema per spec §40.9 (matches existing AuditLog domain model)
- domain enum stored as VARCHAR(50) — no CHECK constraint (allows future extension)
- data JSONB for arbitrary safe metadata
- ip_address stores hashed/masked value (not raw IP) — enforced at application layer
- indexes: (domain, operation, changed_at), (changed_by, changed_at), (object_id, changed_at)
- deleted_at present for consistency; audit is append-only at application layer"
```

---

## Task 20: Migration V06 — Seed `ADMIN` và `OPERATOR` roles + permissions

**Files:**
- Create: `src/main/resources/db/migration/V202607170905__seed_default_roles.sql`

**Interfaces:**
- Produces: 2 built-in roles + 11 permissions rows seed.

- [ ] **Step 1: Tạo file với deterministic UUIDs**

```sql
-- V202607170905__seed_default_roles.sql
-- Seed 2 built-in roles: ADMIN (all:manage) và OPERATOR (self:* set) — spec §40.10
-- Deterministic UUIDs cho phép integration test match chính xác.

INSERT INTO role (id, role_inherited_id, code, name, description, is_const, status,
                  deleted_at, version,
                  created_by, created_at, last_modified_by, last_modified_at)
VALUES
  ('00000000-0000-0000-0000-000000000001', NULL, 'ADMIN', 'Administrator',
   'System administrator with full access to all resources', TRUE, 'ACTIVE',
   NULL, 0,
   'system', NOW(), 'system', NOW()),
  ('00000000-0000-0000-0000-000000000002', NULL, 'OPERATOR', 'Operator',
   'Business operator with self-service permissions on files and jobs', TRUE, 'ACTIVE',
   NULL, 0,
   'system', NOW(), 'system', NOW());

-- ADMIN permissions: (ALL, MANAGE)
INSERT INTO role_permission (id, role_id, resource_code, action, resource_group,
                             deleted_at,
                             created_by, created_at, last_modified_by, last_modified_at)
VALUES
  (gen_random_uuid(), '00000000-0000-0000-0000-000000000001', 'ALL', 'MANAGE', NULL,
   NULL, 'system', NOW(), 'system', NOW());

-- OPERATOR permissions: SELF_* + read
INSERT INTO role_permission (id, role_id, resource_code, action, resource_group,
                             deleted_at,
                             created_by, created_at, last_modified_by, last_modified_at)
VALUES
  (gen_random_uuid(), '00000000-0000-0000-0000-000000000002', 'FILE',    'SELF_CREATE', 'FILE_PROCESSING', NULL, 'system', NOW(), 'system', NOW()),
  (gen_random_uuid(), '00000000-0000-0000-0000-000000000002', 'FILE',    'SELF_READ',   'FILE_PROCESSING', NULL, 'system', NOW(), 'system', NOW()),
  (gen_random_uuid(), '00000000-0000-0000-0000-000000000002', 'FILE',    'SELF_DELETE', 'FILE_PROCESSING', NULL, 'system', NOW(), 'system', NOW()),
  (gen_random_uuid(), '00000000-0000-0000-0000-000000000002', 'JOB',     'SELF_READ',   'FILE_PROCESSING', NULL, 'system', NOW(), 'system', NOW()),
  (gen_random_uuid(), '00000000-0000-0000-0000-000000000002', 'JOB',     'SELF_UPDATE', 'FILE_PROCESSING', NULL, 'system', NOW(), 'system', NOW()),
  (gen_random_uuid(), '00000000-0000-0000-0000-000000000002', 'REPORT',  'SELF_READ',   'FILE_PROCESSING', NULL, 'system', NOW(), 'system', NOW()),
  (gen_random_uuid(), '00000000-0000-0000-0000-000000000002', 'USER',    'SELF_READ',   'IDENTITY',        NULL, 'system', NOW(), 'system', NOW()),
  (gen_random_uuid(), '00000000-0000-0000-0000-000000000002', 'USER',    'SELF_UPDATE', 'IDENTITY',        NULL, 'system', NOW(), 'system', NOW()),
  (gen_random_uuid(), '00000000-0000-0000-0000-000000000002', 'SESSION', 'SELF_READ',   'IDENTITY',        NULL, 'system', NOW(), 'system', NOW()),
  (gen_random_uuid(), '00000000-0000-0000-0000-000000000002', 'SESSION', 'SELF_DELETE', 'IDENTITY',        NULL, 'system', NOW(), 'system', NOW());
```

**Chú ý:** `gen_random_uuid()` yêu cầu extension `pgcrypto` — thêm vào đầu migration nếu chưa có:

Bổ sung ở đầu file trước INSERT:
```sql
CREATE EXTENSION IF NOT EXISTS pgcrypto;
```

- [ ] **Step 2: Commit**

```bash
git add src/main/resources/db/migration/V202607170905__seed_default_roles.sql
git commit -m "feat(auth): migration V06 seed ADMIN and OPERATOR built-in roles

- ADMIN role with (ALL, MANAGE) super permission — id 00000000-0000-0000-0000-000000000001
- OPERATOR role with 10 SELF_* permissions covering FILE, JOB, REPORT, USER, SESSION
- deterministic role UUIDs for stable integration tests
- ensures pgcrypto extension for gen_random_uuid() on role_permission ids
- is_const=TRUE prevents runtime deletion (enforced at domain layer)"
```

---

## Task 21: JPA entity + repository + mapper + adapter cho `AuditLog`

Vì `AuditLog` là entity đơn giản nhất và các entity khác đều audit_log, ta bắt đầu ở đây.

**Files:**
- Create: `auth/adapter/out/persistence/entity/AuditLogEntity.java`
- Create: `auth/adapter/out/persistence/entity/JpaAuditLogRepository.java`
- Create: `auth/adapter/out/persistence/mapper/AuditLogPersistenceMapper.java`
- Create: `auth/application/port/out/AuditLogPort.java`
- Create: `auth/adapter/out/persistence/AuditLogPersistenceAdapter.java`

**Interfaces:**
- Consumes: `AuditLog` domain (đã có), `AuditLogDomain`, `OperationType`.
- Produces:
  - `AuditLogPort` interface với method `void record(AuditLog log)`, `Page<AuditLog> search(...)` (search sẽ implement chi tiết ở Phase 5, Phase 1+2 chỉ cần `record`).

- [ ] **Step 1: Tạo `AuditLogEntity.java`**

```java
package com.vandunxg.file_processing.auth.adapter.out.persistence.entity;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.vandunxg.common.models.entities.AuditableEntity;
import com.vandunxg.file_processing.auth.domain.model.AuditLogDomain;
import com.vandunxg.file_processing.auth.domain.model.OperationType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Entity
@Table(name = "audit_logs")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false, of = "id")
public class AuditLogEntity extends AuditableEntity {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Enumerated(EnumType.STRING)
  @Column(name = "domain", length = 50, nullable = false)
  private AuditLogDomain domain;

  @Column(name = "object_id")
  private UUID objectId;

  @Enumerated(EnumType.STRING)
  @Column(name = "operation", length = 50, nullable = false)
  private OperationType operation;

  @Column(name = "changed_by")
  private UUID changedBy;

  @Column(name = "changed_at", nullable = false)
  private Instant changedAt;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "data", columnDefinition = "jsonb")
  private Map<String, Object> data;

  @Column(name = "ip_address", length = 64)
  private String ipAddress;

  @Column(name = "browser", length = 64)
  private String browser;

  @Column(name = "user_agent", length = 200)
  private String userAgent;

  @Column(name = "deleted_at")
  private Instant deletedAt;
}
```

- [ ] **Step 2: Tạo `JpaAuditLogRepository.java`**

```java
package com.vandunxg.file_processing.auth.adapter.out.persistence.entity;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface JpaAuditLogRepository extends JpaRepository<AuditLogEntity, UUID> {}
```

- [ ] **Step 3: Tạo `AuditLogPersistenceMapper.java`**

```java
package com.vandunxg.file_processing.auth.adapter.out.persistence.mapper;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

import com.vandunxg.common.models.mapper.EntityMapper;
import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.AuditLogEntity;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;

@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface AuditLogPersistenceMapper extends EntityMapper<AuditLog, AuditLogEntity> {

  @Override
  AuditLog toDomain(AuditLogEntity entity);

  @Override
  @Mapping(target = "createdAt", ignore = true)
  @Mapping(target = "lastModifiedAt", ignore = true)
  @Mapping(target = "createdBy", ignore = true)
  @Mapping(target = "lastModifiedBy", ignore = true)
  AuditLogEntity toEntity(AuditLog domain);

  @Override
  List<AuditLog> toDomain(List<AuditLogEntity> entities);

  @Override
  List<AuditLogEntity> toEntity(List<AuditLog> domains);
}
```

- [ ] **Step 4: Tạo `AuditLogPort.java`**

```java
package com.vandunxg.file_processing.auth.application.port.out;

import com.vandunxg.file_processing.auth.domain.model.AuditLog;

public interface AuditLogPort {

  void record(AuditLog log);
}
```

- [ ] **Step 5: Tạo `AuditLogPersistenceAdapter.java`**

```java
package com.vandunxg.file_processing.auth.adapter.out.persistence;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.JpaAuditLogRepository;
import com.vandunxg.file_processing.auth.adapter.out.persistence.mapper.AuditLogPersistenceMapper;
import com.vandunxg.file_processing.auth.application.port.out.AuditLogPort;
import com.vandunxg.file_processing.auth.domain.model.AuditLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Service
@RequiredArgsConstructor
@Slf4j(topic = "AUTH-AUDIT")
public class AuditLogPersistenceAdapter implements AuditLogPort {

  private final JpaAuditLogRepository repository;
  private final AuditLogPersistenceMapper mapper;

  @Override
  @Transactional
  public void record(AuditLog log) {
    var entity = mapper.toEntity(log);
    repository.save(entity);
  }
}
```

- [ ] **Step 6: Build kiểm tra**

```bash
./mvnw -DskipTests clean compile
```
Expected: BUILD SUCCESS. MapStruct-generated `AuditLogPersistenceMapperImpl.java` xuất hiện trong `target/generated-sources/annotations/`.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/vandunxg/file_processing/auth/adapter/out/persistence/entity/AuditLogEntity.java src/main/java/com/vandunxg/file_processing/auth/adapter/out/persistence/entity/JpaAuditLogRepository.java src/main/java/com/vandunxg/file_processing/auth/adapter/out/persistence/mapper/AuditLogPersistenceMapper.java src/main/java/com/vandunxg/file_processing/auth/application/port/out/AuditLogPort.java src/main/java/com/vandunxg/file_processing/auth/adapter/out/persistence/AuditLogPersistenceAdapter.java
git commit -m "feat(auth): AuditLog persistence — entity + mapper + port + adapter

- AuditLogEntity maps to shared audit_logs table
- data field uses Hibernate 6 @JdbcTypeCode(SqlTypes.JSON) for jsonb
- MapStruct mapper implements EntityMapper<AuditLog, AuditLogEntity>; ignores audit fields on toEntity
- AuditLogPort.record(AuditLog) minimal interface for Phase 1+2 (search will be added in Phase 5)
- adapter @Transactional saves via JpaAuditLogRepository"
```

---

## Task 22-25 và Task 26-30 — Chi tiết trong file plan

**Do plan file này đã dài ~1300 dòng và còn Task 22-30 sẽ thêm ~1500 dòng nữa, tôi split tiếp thành Part 2b (Task 22-30).**

Task còn lại của Part 2 (đưa vào file `2026-07-17-auth-part2b-persistence-adapters.md`):

- **Task 22:** JPA UserEntity + JpaUserRepository (custom query) + UserPersistenceMapper + UserRepositoryPort + UserPersistenceAdapter (findByNormalizedIdentifier, save, countActiveAdmins).
- **Task 23:** JPA RoleEntity + JpaRoleRepository + RolePersistenceMapper + RoleRepositoryPort + RolePersistenceAdapter.
- **Task 24:** JPA RolePermissionEntity + JpaRolePermissionRepository + RolePermissionPersistenceMapper + RolePermissionRepositoryPort + RolePermissionPersistenceAdapter.
- **Task 25:** JPA UserRoleEntity + JpaUserRoleRepository + UserRolePersistenceMapper + UserRoleRepositoryPort + UserRolePersistenceAdapter.
- **Task 26:** `PasswordHasherPort` + `BcryptPasswordHasherAdapter` (dùng `DelegatingPasswordEncoder`, prefix `{bcrypt}`, cost từ `AuthProperties`).
- **Task 27:** `ClockPort` + `SystemClockAdapter` (wraps `Clock.systemUTC()`), `IdGeneratorPort` + `UuidIdGeneratorAdapter` (wraps `IdUtils.nextId()`).
- **Task 28:** `AuthProperties` typed `@ConfigurationProperties(prefix = "app.auth")` record + `AuthPersistenceConfiguration @Configuration @EnableJpaAuditing @EnableConfigurationProperties(AuthProperties.class)`.
- **Task 29:** IT `MigrationAndSeedIT` — kiểm tra `SELECT count(*) FROM role WHERE is_const = TRUE` = 2, tổng permission = 11, verify constraint và index.
- **Task 30:** IT `LastActiveAdminQueryIT` — insert admin, thử disable → OK; insert admin thứ 2, disable admin 1 → OK; disable admin 2 → validation fail via query `countActiveAdmins()` returning 0.

---

## Kết thúc Part 2

Sau khi hoàn thành 16 task (15-30), bạn có:
- 6 Flyway migrations chạy sạch tạo full schema + seed 2 built-in role + 11 permissions.
- 5 JPA entity (`UserEntity`, `RoleEntity`, `RolePermissionEntity`, `UserRoleEntity`, `AuditLogEntity`) + Spring Data repository.
- 5 MapStruct mapper (domain ↔ entity).
- 5 outbound port + 5 persistence adapter.
- `PasswordHasherPort` + BCrypt adapter.
- `ClockPort`, `IdGeneratorPort` + system adapter.
- `AuthProperties` typed config.
- IT chứng minh migration + seed đúng, `countActiveAdmins()` chống race last-active-admin.

**Chuyển sang Part 3** (`2026-07-17-auth-part3-jwt-and-login.md`) — JWT service + JWKS + CredentialVersion cache + LoginUseCase + BootstrapAdmin.

**Chú ý:** Task 22-30 (persistence adapter chi tiết cho User/Role/RolePermission/UserRole + PasswordHasher/Clock/IdGenerator/AuthProperties + IT) tách sang file `2026-07-17-auth-part2b-persistence-adapters.md` do độ dài. Xem file đó để thấy code chi tiết cho từng aggregate.
