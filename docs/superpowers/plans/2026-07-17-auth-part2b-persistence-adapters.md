# Auth Module — Part 2b: Persistence Adapters (User, Role, RolePermission, UserRole, Password/Clock/Id, AuthProperties, IT)

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` or `superpowers:executing-plans`.

> **Part 2b of 4** — Prerequisite: Part 1 + Part 2 (Task 1-21). Extends Part 2 với Task 22-30 chi tiết.

**Goal:** Hoàn thiện outbound persistence layer cho User/Role/RolePermission/UserRole; ship `PasswordHasherPort`+BCrypt, `ClockPort`, `IdGeneratorPort`, `AuthProperties` typed config; IT verify migration + last-active-admin invariant.

Kế thừa Global Constraints và File Structure từ Part 2.

---

## Task 22: `User` persistence — entity, repo, mapper, port, adapter

**Files:**
- Create: `auth/adapter/out/persistence/entity/UserEntity.java`
- Rewrite: `auth/adapter/out/persistence/entity/JpaUserRepository.java`
- Create: `auth/adapter/out/persistence/mapper/UserPersistenceMapper.java`
- Rewrite: `auth/application/port/out/UserRepositoryPort.java`
- Create: `auth/adapter/out/persistence/UserPersistenceAdapter.java`

**Interfaces:**
- Consumes: `User`, `Role`, `UserRole` domain; `Instant`; `PageDTO`.
- Produces:
  - `UserRepositoryPort`:
    - `Optional<User> findById(UUID id)`
    - `Optional<User> findByNormalizedIdentifier(String identifier)` — try username first, fallback email
    - `Optional<User> findByNormalizedUsername(String normalized)`
    - `Optional<User> findByNormalizedEmail(String normalized)`
    - `boolean existsByNormalizedUsername(String normalized)`
    - `boolean existsByNormalizedEmail(String normalized)`
    - `User save(User user)` — insert hoặc update
    - `long countUsers()`  — cho bootstrap
    - `long countActiveAdmins()` — cho last-active-admin invariant
    - `User loadWithRoles(UUID id)` — eager load roles (hoặc query riêng)

- [ ] **Step 1: Tạo `UserEntity.java`**

```java
package com.vandunxg.file_processing.auth.adapter.out.persistence.entity;

import java.time.Instant;
import java.util.UUID;

import com.vandunxg.common.models.entities.AuditableEntity;
import com.vandunxg.file_processing.auth.domain.model.UserStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Entity
@Table(name = "auth_users")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false, of = "id")
public class UserEntity extends AuditableEntity {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "username", length = 64, nullable = false)
  private String username;

  @Column(name = "normalized_username", length = 64, nullable = false)
  private String normalizedUsername;

  @Column(name = "email", length = 254, nullable = false)
  private String email;

  @Column(name = "normalized_email", length = 254, nullable = false)
  private String normalizedEmail;

  @Column(name = "display_name", length = 150, nullable = false)
  private String displayName;

  @Column(name = "password_hash", length = 255, nullable = false)
  private String passwordHash;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", length = 20, nullable = false)
  private UserStatus status;

  @Column(name = "must_change_password", nullable = false)
  private boolean mustChangePassword;

  @Column(name = "failed_login_count", nullable = false)
  private int failedLoginCount;

  @Column(name = "last_failed_login_at")
  private Instant lastFailedLoginAt;

  @Column(name = "locked_until")
  private Instant lockedUntil;

  @Column(name = "credential_version", nullable = false)
  private int credentialVersion;

  @Column(name = "last_login_at")
  private Instant lastLoginAt;

  @Column(name = "password_changed_at", nullable = false)
  private Instant passwordChangedAt;

  @Column(name = "email_verified_at")
  private Instant emailVerifiedAt;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  @Version
  @Column(name = "version", nullable = false)
  private Long version;
}
```

- [ ] **Step 2: Rewrite `JpaUserRepository.java`**

```java
package com.vandunxg.file_processing.auth.adapter.out.persistence.entity;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaUserRepository extends JpaRepository<UserEntity, UUID> {

  @Query("SELECT u FROM UserEntity u WHERE u.normalizedUsername = :n AND u.deletedAt IS NULL")
  Optional<UserEntity> findByNormalizedUsernameActive(@Param("n") String normalized);

  @Query("SELECT u FROM UserEntity u WHERE u.normalizedEmail = :e AND u.deletedAt IS NULL")
  Optional<UserEntity> findByNormalizedEmailActive(@Param("e") String normalized);

  @Query("SELECT COUNT(u) FROM UserEntity u WHERE u.deletedAt IS NULL")
  long countActive();

  /**
   * Đếm số Admin đang ACTIVE (dùng cho last-active-admin invariant).
   * ADMIN role code cố định = 'ADMIN', là role built-in (id = 00000000-0000-0000-0000-000000000001).
   */
  @Query("""
      SELECT COUNT(DISTINCT u)
      FROM UserEntity u, UserRoleEntity ur, RoleEntity r
      WHERE ur.userId = u.id
        AND ur.roleId = r.id
        AND ur.deletedAt IS NULL
        AND r.deletedAt IS NULL
        AND r.code = 'ADMIN'
        AND r.status = com.vandunxg.file_processing.auth.domain.model.ActiveStatus.ACTIVE
        AND u.status = com.vandunxg.file_processing.auth.domain.model.UserStatus.ACTIVE
        AND u.deletedAt IS NULL
      """)
  long countActiveAdmins();

  boolean existsByNormalizedUsername(String normalizedUsername);

  boolean existsByNormalizedEmail(String normalizedEmail);
}
```

- [ ] **Step 3: Tạo `UserPersistenceMapper.java`**

```java
package com.vandunxg.file_processing.auth.adapter.out.persistence.mapper;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

import com.vandunxg.common.models.mapper.EntityMapper;
import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.UserEntity;
import com.vandunxg.file_processing.auth.domain.model.User;

@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface UserPersistenceMapper extends EntityMapper<User, UserEntity> {

  @Override
  @Mapping(target = "roles", ignore = true) // roles loaded separately via UserRoleRepository
  User toDomain(UserEntity entity);

  @Override
  @Mapping(target = "createdAt",     ignore = true)
  @Mapping(target = "lastModifiedAt", ignore = true)
  @Mapping(target = "createdBy",     ignore = true)
  @Mapping(target = "lastModifiedBy", ignore = true)
  UserEntity toEntity(User domain);

  @Override
  List<User> toDomain(List<UserEntity> entities);

  @Override
  List<UserEntity> toEntity(List<User> domains);
}
```

- [ ] **Step 4: Rewrite `UserRepositoryPort.java` — convert từ class stub sang interface**

```java
package com.vandunxg.file_processing.auth.application.port.out;

import java.util.Optional;
import java.util.UUID;

import com.vandunxg.file_processing.auth.domain.model.User;

public interface UserRepositoryPort {

  Optional<User> findById(UUID id);

  Optional<User> findByNormalizedUsername(String normalized);

  Optional<User> findByNormalizedEmail(String normalized);

  Optional<User> findByNormalizedIdentifier(String identifier);

  boolean existsByNormalizedUsername(String normalized);

  boolean existsByNormalizedEmail(String normalized);

  User save(User user);

  long countUsers();

  long countActiveAdmins();
}
```

- [ ] **Step 5: Tạo `UserPersistenceAdapter.java`**

```java
package com.vandunxg.file_processing.auth.adapter.out.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.JpaUserRepository;
import com.vandunxg.file_processing.auth.adapter.out.persistence.mapper.UserPersistenceMapper;
import com.vandunxg.file_processing.auth.application.port.out.UserRepositoryPort;
import com.vandunxg.file_processing.auth.domain.model.User;
import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class UserPersistenceAdapter implements UserRepositoryPort {

  private final JpaUserRepository jpa;
  private final UserPersistenceMapper mapper;

  @Override
  @Transactional(readOnly = true)
  public Optional<User> findById(UUID id) {
    return jpa.findById(id).map(mapper::toDomain);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<User> findByNormalizedUsername(String normalized) {
    return jpa.findByNormalizedUsernameActive(normalized).map(mapper::toDomain);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<User> findByNormalizedEmail(String normalized) {
    return jpa.findByNormalizedEmailActive(normalized).map(mapper::toDomain);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<User> findByNormalizedIdentifier(String identifier) {
    if (identifier == null || identifier.isBlank()) return Optional.empty();
    String norm = identifier.trim().toLowerCase();
    if (norm.contains("@")) {
      return jpa.findByNormalizedEmailActive(norm).map(mapper::toDomain);
    }
    return jpa.findByNormalizedUsernameActive(norm).map(mapper::toDomain);
  }

  @Override
  @Transactional(readOnly = true)
  public boolean existsByNormalizedUsername(String normalized) {
    return jpa.existsByNormalizedUsername(normalized);
  }

  @Override
  @Transactional(readOnly = true)
  public boolean existsByNormalizedEmail(String normalized) {
    return jpa.existsByNormalizedEmail(normalized);
  }

  @Override
  @Transactional
  public User save(User user) {
    var entity = mapper.toEntity(user);
    var saved = jpa.save(entity);
    return mapper.toDomain(saved);
  }

  @Override
  @Transactional(readOnly = true)
  public long countUsers() {
    return jpa.countActive();
  }

  @Override
  @Transactional(readOnly = true)
  public long countActiveAdmins() {
    return jpa.countActiveAdmins();
  }
}
```

- [ ] **Step 6: Build**

```bash
./mvnw -DskipTests clean compile
```
Expected: BUILD SUCCESS.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/vandunxg/file_processing/auth/adapter/out/persistence/entity/UserEntity.java src/main/java/com/vandunxg/file_processing/auth/adapter/out/persistence/entity/JpaUserRepository.java src/main/java/com/vandunxg/file_processing/auth/adapter/out/persistence/mapper/UserPersistenceMapper.java src/main/java/com/vandunxg/file_processing/auth/application/port/out/UserRepositoryPort.java src/main/java/com/vandunxg/file_processing/auth/adapter/out/persistence/UserPersistenceAdapter.java
git commit -m "feat(auth): User persistence — entity, repository, mapper, port, adapter

- UserEntity maps to auth_users with @Version optimistic lock
- JpaUserRepository custom queries: findByNormalizedUsernameActive, findByNormalizedEmailActive, countActive, countActiveAdmins (JPQL join user_role + role for last-active-admin invariant)
- UserPersistenceMapper implements EntityMapper<User, UserEntity>; roles field ignored (loaded separately)
- UserRepositoryPort defines findByNormalizedIdentifier auto-detect email via @, exists*, save, count*
- UserPersistenceAdapter @Transactional read/write methods"
```

---

## Task 23: `Role` persistence

**Files:**
- Create: `auth/adapter/out/persistence/entity/RoleEntity.java`
- Create: `auth/adapter/out/persistence/entity/JpaRoleRepository.java`
- Create: `auth/adapter/out/persistence/mapper/RolePersistenceMapper.java`
- Create: `auth/application/port/out/RoleRepositoryPort.java`
- Create: `auth/adapter/out/persistence/RolePersistenceAdapter.java`

**Interfaces:**
- Produces:
  - `RoleRepositoryPort`:
    - `Optional<Role> findById(UUID id)` — hydrates permissions
    - `Optional<Role> findByCode(String code)` — hydrates permissions
    - `List<Role> findAllActive()` — hydrates permissions
    - `Role save(Role role)`
    - `boolean existsByCode(String code)`
    - `List<Role> findByIds(Collection<UUID> ids)`
    - `List<Role> findDescendants(UUID parentId)` — for role inheritance propagation

- [ ] **Step 1: Tạo `RoleEntity.java`**

```java
package com.vandunxg.file_processing.auth.adapter.out.persistence.entity;

import java.time.Instant;
import java.util.UUID;

import com.vandunxg.common.models.entities.AuditableEntity;
import com.vandunxg.file_processing.auth.domain.model.ActiveStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Entity
@Table(name = "role")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false, of = "id")
public class RoleEntity extends AuditableEntity {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "role_inherited_id")
  private UUID roleInheritedId;

  @Column(name = "code", length = 50, nullable = false)
  private String code;

  @Column(name = "name", length = 100, nullable = false)
  private String name;

  @Column(name = "description", length = 1000)
  private String description;

  @Column(name = "is_const")
  private Boolean isConst;

  @Enumerated(EnumType.STRING)
  @Column(name = "status", length = 20, nullable = false)
  private ActiveStatus status;

  @Column(name = "deleted_at")
  private Instant deletedAt;

  @Version
  @Column(name = "version", nullable = false)
  private Long version;
}
```

- [ ] **Step 2: Tạo `JpaRoleRepository.java`**

```java
package com.vandunxg.file_processing.auth.adapter.out.persistence.entity;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaRoleRepository extends JpaRepository<RoleEntity, UUID> {

  @Query("SELECT r FROM RoleEntity r WHERE r.code = :c AND r.deletedAt IS NULL")
  Optional<RoleEntity> findByCodeActive(@Param("c") String code);

  @Query("SELECT r FROM RoleEntity r WHERE r.id IN :ids AND r.deletedAt IS NULL")
  List<RoleEntity> findAllByIdsActive(@Param("ids") Collection<UUID> ids);

  @Query("SELECT r FROM RoleEntity r WHERE r.deletedAt IS NULL")
  List<RoleEntity> findAllActive();

  boolean existsByCodeAndDeletedAtIsNull(String code);

  /**
   * Trả về danh sách role con (bao gồm role hiện tại) qua chain inheritance.
   * Thực hiện bằng recursive CTE (native query PostgreSQL).
   */
  @Query(
      value = """
          WITH RECURSIVE descendants AS (
              SELECT * FROM role WHERE id = :rootId AND deleted_at IS NULL
              UNION ALL
              SELECT r.* FROM role r
              INNER JOIN descendants d ON r.role_inherited_id = d.id
              WHERE r.deleted_at IS NULL
          )
          SELECT * FROM descendants
          """,
      nativeQuery = true)
  List<RoleEntity> findDescendants(@Param("rootId") UUID rootId);
}
```

- [ ] **Step 3: Tạo `RolePersistenceMapper.java`**

```java
package com.vandunxg.file_processing.auth.adapter.out.persistence.mapper;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

import com.vandunxg.common.models.mapper.EntityMapper;
import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.RoleEntity;
import com.vandunxg.file_processing.auth.domain.model.Role;

@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface RolePersistenceMapper extends EntityMapper<Role, RoleEntity> {

  @Override
  @Mapping(target = "permissions",         ignore = true)
  @Mapping(target = "userRoles",           ignore = true)
  @Mapping(target = "roleInheritedName",   ignore = true)
  @Mapping(target = "roleInheritedCode",   ignore = true)
  Role toDomain(RoleEntity entity);

  @Override
  @Mapping(target = "createdAt",     ignore = true)
  @Mapping(target = "lastModifiedAt", ignore = true)
  @Mapping(target = "createdBy",     ignore = true)
  @Mapping(target = "lastModifiedBy", ignore = true)
  RoleEntity toEntity(Role domain);

  @Override
  List<Role> toDomain(List<RoleEntity> entities);

  @Override
  List<RoleEntity> toEntity(List<Role> domains);
}
```

- [ ] **Step 4: Tạo `RoleRepositoryPort.java`**

```java
package com.vandunxg.file_processing.auth.application.port.out;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.vandunxg.file_processing.auth.domain.model.Role;

public interface RoleRepositoryPort {

  Optional<Role> findById(UUID id);

  Optional<Role> findByCode(String code);

  List<Role> findByIds(Collection<UUID> ids);

  List<Role> findAllActive();

  boolean existsByCode(String code);

  Role save(Role role);

  List<Role> findDescendants(UUID rootId);
}
```

- [ ] **Step 5: Tạo `RolePersistenceAdapter.java`**

Adapter cần hydrate `permissions` (từ `RolePermissionRepositoryPort`) sau khi load. Vì `RolePermissionRepositoryPort` chưa tồn tại (sẽ tạo ở Task 24), tạm thời để `permissions = List.of()` — sẽ enrich sau khi tạo được `RolePermissionRepositoryPort`. Cách sạch nhất là inject `RolePermissionRepositoryPort` vào adapter.

Vậy Task 24 hoàn thiện trước tôi mới có thể hoàn thiện `RolePersistenceAdapter`. Ta viết adapter version đơn giản (chỉ Role, không hydrate permissions) trước, sau đó Task 24 sẽ nâng cấp.

Version 1 (Task 23):

```java
package com.vandunxg.file_processing.auth.adapter.out.persistence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.JpaRoleRepository;
import com.vandunxg.file_processing.auth.adapter.out.persistence.mapper.RolePersistenceMapper;
import com.vandunxg.file_processing.auth.application.port.out.RoleRepositoryPort;
import com.vandunxg.file_processing.auth.domain.model.Role;
import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class RolePersistenceAdapter implements RoleRepositoryPort {

  private final JpaRoleRepository jpa;
  private final RolePersistenceMapper mapper;

  @Override
  @Transactional(readOnly = true)
  public Optional<Role> findById(UUID id) {
    return jpa.findById(id).map(mapper::toDomain);
  }

  @Override
  @Transactional(readOnly = true)
  public Optional<Role> findByCode(String code) {
    return jpa.findByCodeActive(code).map(mapper::toDomain);
  }

  @Override
  @Transactional(readOnly = true)
  public List<Role> findByIds(Collection<UUID> ids) {
    if (ids == null || ids.isEmpty()) return new ArrayList<>();
    return mapper.toDomain(jpa.findAllByIdsActive(ids));
  }

  @Override
  @Transactional(readOnly = true)
  public List<Role> findAllActive() {
    return mapper.toDomain(jpa.findAllActive());
  }

  @Override
  @Transactional(readOnly = true)
  public boolean existsByCode(String code) {
    return jpa.existsByCodeAndDeletedAtIsNull(code);
  }

  @Override
  @Transactional
  public Role save(Role role) {
    var entity = mapper.toEntity(role);
    var saved = jpa.save(entity);
    return mapper.toDomain(saved);
  }

  @Override
  @Transactional(readOnly = true)
  public List<Role> findDescendants(UUID rootId) {
    if (rootId == null) return new ArrayList<>();
    return mapper.toDomain(jpa.findDescendants(rootId));
  }
}
```

**Chú ý:** Đang không hydrate permissions. Task 24 sẽ thêm `RolePermissionRepositoryPort` và Task 32 (`AuthorityService`) sẽ query permissions riêng — không cần hydrate mọi lần load Role.

- [ ] **Step 6: Build**

```bash
./mvnw -DskipTests clean compile
```

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/vandunxg/file_processing/auth/adapter/out/persistence/entity/RoleEntity.java src/main/java/com/vandunxg/file_processing/auth/adapter/out/persistence/entity/JpaRoleRepository.java src/main/java/com/vandunxg/file_processing/auth/adapter/out/persistence/mapper/RolePersistenceMapper.java src/main/java/com/vandunxg/file_processing/auth/application/port/out/RoleRepositoryPort.java src/main/java/com/vandunxg/file_processing/auth/adapter/out/persistence/RolePersistenceAdapter.java
git commit -m "feat(auth): Role persistence — entity, repo, mapper, port, adapter

- RoleEntity maps to 'role' table with self-FK role_inherited_id
- JpaRoleRepository: findByCodeActive, findAllByIdsActive, findAllActive, existsByCodeAndDeletedAtIsNull
- findDescendants uses PostgreSQL recursive CTE walking role_inherited_id chain
- RolePersistenceMapper implements EntityMapper<Role, RoleEntity>; permissions/userRoles/inherited* enriched separately
- Adapter methods @Transactional (read/write)"
```

---

## Task 24: `RolePermission` persistence + hydrate Role.permissions

**Files:**
- Create: `auth/adapter/out/persistence/entity/RolePermissionEntity.java`
- Create: `auth/adapter/out/persistence/entity/JpaRolePermissionRepository.java`
- Create: `auth/adapter/out/persistence/mapper/RolePermissionPersistenceMapper.java`
- Create: `auth/application/port/out/RolePermissionRepositoryPort.java`
- Create: `auth/adapter/out/persistence/RolePermissionPersistenceAdapter.java`

**Interfaces:**
- Produces:
  - `RolePermissionRepositoryPort`:
    - `List<RolePermission> findByRoleId(UUID roleId)` — chỉ active
    - `List<RolePermission> findByRoleIds(Collection<UUID> roleIds)`
    - `List<RolePermission> findAllByUserId(UUID userId)` — join với user_role
    - `void saveAll(Collection<RolePermission> permissions)`

- [ ] **Step 1: Tạo `RolePermissionEntity.java`**

```java
package com.vandunxg.file_processing.auth.adapter.out.persistence.entity;

import java.time.Instant;
import java.util.UUID;

import com.vandunxg.common.models.entities.AuditableEntity;
import com.vandunxg.common.models.enums.Action;
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
@Table(name = "role_permission")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false, of = "id")
public class RolePermissionEntity extends AuditableEntity {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "role_id", nullable = false)
  private UUID roleId;

  @Column(name = "resource_code", length = 50, nullable = false)
  private String resourceCode;

  @Enumerated(EnumType.STRING)
  @Column(name = "action", length = 20, nullable = false)
  private Action action;

  @Column(name = "resource_group", length = 255)
  private String resourceGroup;

  @Column(name = "deleted_at")
  private Instant deletedAt;
}
```

- [ ] **Step 2: Tạo `JpaRolePermissionRepository.java`**

```java
package com.vandunxg.file_processing.auth.adapter.out.persistence.entity;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaRolePermissionRepository extends JpaRepository<RolePermissionEntity, UUID> {

  @Query("SELECT rp FROM RolePermissionEntity rp WHERE rp.roleId = :rid AND rp.deletedAt IS NULL")
  List<RolePermissionEntity> findActiveByRoleId(@Param("rid") UUID roleId);

  @Query("SELECT rp FROM RolePermissionEntity rp WHERE rp.roleId IN :rids AND rp.deletedAt IS NULL")
  List<RolePermissionEntity> findActiveByRoleIds(@Param("rids") Collection<UUID> roleIds);

  /**
   * Trả về tất cả active permissions của các role mà user đang thuộc.
   * Join qua user_role (deleted_at IS NULL) + role (deleted_at IS NULL, status = ACTIVE).
   */
  @Query("""
      SELECT rp
      FROM RolePermissionEntity rp, UserRoleEntity ur, RoleEntity r
      WHERE ur.userId = :uid
        AND ur.deletedAt IS NULL
        AND ur.roleId = r.id
        AND r.deletedAt IS NULL
        AND r.status = com.vandunxg.file_processing.auth.domain.model.ActiveStatus.ACTIVE
        AND rp.roleId = r.id
        AND rp.deletedAt IS NULL
      """)
  List<RolePermissionEntity> findAllActiveByUserId(@Param("uid") UUID userId);
}
```

- [ ] **Step 3: Tạo `RolePermissionPersistenceMapper.java`**

```java
package com.vandunxg.file_processing.auth.adapter.out.persistence.mapper;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

import com.vandunxg.common.models.mapper.EntityMapper;
import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.RolePermissionEntity;
import com.vandunxg.file_processing.auth.domain.model.RolePermission;

@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface RolePermissionPersistenceMapper
    extends EntityMapper<RolePermission, RolePermissionEntity> {

  @Override
  RolePermission toDomain(RolePermissionEntity entity);

  @Override
  @Mapping(target = "createdAt",      ignore = true)
  @Mapping(target = "lastModifiedAt", ignore = true)
  @Mapping(target = "createdBy",      ignore = true)
  @Mapping(target = "lastModifiedBy", ignore = true)
  RolePermissionEntity toEntity(RolePermission domain);

  @Override
  List<RolePermission> toDomain(List<RolePermissionEntity> entities);

  @Override
  List<RolePermissionEntity> toEntity(List<RolePermission> domains);
}
```

- [ ] **Step 4: Tạo `RolePermissionRepositoryPort.java`**

```java
package com.vandunxg.file_processing.auth.application.port.out;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.vandunxg.file_processing.auth.domain.model.RolePermission;

public interface RolePermissionRepositoryPort {

  List<RolePermission> findByRoleId(UUID roleId);

  List<RolePermission> findByRoleIds(Collection<UUID> roleIds);

  List<RolePermission> findAllByUserId(UUID userId);

  void saveAll(Collection<RolePermission> permissions);
}
```

- [ ] **Step 5: Tạo `RolePermissionPersistenceAdapter.java`**

```java
package com.vandunxg.file_processing.auth.adapter.out.persistence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.JpaRolePermissionRepository;
import com.vandunxg.file_processing.auth.adapter.out.persistence.mapper.RolePermissionPersistenceMapper;
import com.vandunxg.file_processing.auth.application.port.out.RolePermissionRepositoryPort;
import com.vandunxg.file_processing.auth.domain.model.RolePermission;
import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class RolePermissionPersistenceAdapter implements RolePermissionRepositoryPort {

  private final JpaRolePermissionRepository jpa;
  private final RolePermissionPersistenceMapper mapper;

  @Override
  @Transactional(readOnly = true)
  public List<RolePermission> findByRoleId(UUID roleId) {
    return mapper.toDomain(jpa.findActiveByRoleId(roleId));
  }

  @Override
  @Transactional(readOnly = true)
  public List<RolePermission> findByRoleIds(Collection<UUID> roleIds) {
    if (roleIds == null || roleIds.isEmpty()) return new ArrayList<>();
    return mapper.toDomain(jpa.findActiveByRoleIds(roleIds));
  }

  @Override
  @Transactional(readOnly = true)
  public List<RolePermission> findAllByUserId(UUID userId) {
    return mapper.toDomain(jpa.findAllActiveByUserId(userId));
  }

  @Override
  @Transactional
  public void saveAll(Collection<RolePermission> permissions) {
    if (permissions == null || permissions.isEmpty()) return;
    jpa.saveAll(mapper.toEntity(new ArrayList<>(permissions)));
  }
}
```

- [ ] **Step 6: Build + commit**

```bash
./mvnw -DskipTests clean compile
git add src/main/java/com/vandunxg/file_processing/auth/adapter/out/persistence/entity/RolePermissionEntity.java src/main/java/com/vandunxg/file_processing/auth/adapter/out/persistence/entity/JpaRolePermissionRepository.java src/main/java/com/vandunxg/file_processing/auth/adapter/out/persistence/mapper/RolePermissionPersistenceMapper.java src/main/java/com/vandunxg/file_processing/auth/application/port/out/RolePermissionRepositoryPort.java src/main/java/com/vandunxg/file_processing/auth/adapter/out/persistence/RolePermissionPersistenceAdapter.java
git commit -m "feat(auth): RolePermission persistence + user's active permissions query

- RolePermissionEntity with Action enum stored as VARCHAR
- JpaRolePermissionRepository: findActiveByRoleId(s), findAllActiveByUserId (joins user_role + role, filters deleted/inactive)
- RolePermissionRepositoryPort methods for role hydration and AuthorityService permission resolution
- adapter @Transactional operations"
```

---

## Task 25: `UserRole` persistence

**Files:**
- Create: `auth/adapter/out/persistence/entity/UserRoleEntity.java`
- Create: `auth/adapter/out/persistence/entity/JpaUserRoleRepository.java`
- Create: `auth/adapter/out/persistence/mapper/UserRolePersistenceMapper.java`
- Create: `auth/application/port/out/UserRoleRepositoryPort.java`
- Create: `auth/adapter/out/persistence/UserRolePersistenceAdapter.java`

**Interfaces:**
- Produces:
  - `UserRoleRepositoryPort`:
    - `List<UserRole> findByUserId(UUID userId)` — chỉ active
    - `List<UserID> findUserIdsByRoleId(UUID roleId)` — cho invalidate credential khi role thay đổi
    - `void saveAll(Collection<UserRole> assignments)`
    - `long countByRoleId(UUID roleId)`

- [ ] **Step 1: Tạo entity**

```java
package com.vandunxg.file_processing.auth.adapter.out.persistence.entity;

import java.time.Instant;
import java.util.UUID;

import com.vandunxg.common.models.entities.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@Entity
@Table(name = "user_role")
@Getter
@Setter
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode(callSuper = false, of = "id")
public class UserRoleEntity extends AuditableEntity {

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "role_id", nullable = false)
  private UUID roleId;

  @Column(name = "deleted_at")
  private Instant deletedAt;
}
```

- [ ] **Step 2: Tạo repository**

```java
package com.vandunxg.file_processing.auth.adapter.out.persistence.entity;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface JpaUserRoleRepository extends JpaRepository<UserRoleEntity, UUID> {

  @Query("SELECT ur FROM UserRoleEntity ur WHERE ur.userId = :uid AND ur.deletedAt IS NULL")
  List<UserRoleEntity> findActiveByUserId(@Param("uid") UUID userId);

  @Query("SELECT ur.userId FROM UserRoleEntity ur WHERE ur.roleId = :rid AND ur.deletedAt IS NULL")
  List<UUID> findUserIdsByRoleId(@Param("rid") UUID roleId);

  long countByRoleIdAndDeletedAtIsNull(UUID roleId);
}
```

- [ ] **Step 3: Tạo mapper**

```java
package com.vandunxg.file_processing.auth.adapter.out.persistence.mapper;

import java.util.List;

import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

import com.vandunxg.common.models.mapper.EntityMapper;
import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.UserRoleEntity;
import com.vandunxg.file_processing.auth.domain.model.UserRole;

@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface UserRolePersistenceMapper extends EntityMapper<UserRole, UserRoleEntity> {

  @Override
  UserRole toDomain(UserRoleEntity entity);

  @Override
  @Mapping(target = "createdAt",      ignore = true)
  @Mapping(target = "lastModifiedAt", ignore = true)
  @Mapping(target = "createdBy",      ignore = true)
  @Mapping(target = "lastModifiedBy", ignore = true)
  UserRoleEntity toEntity(UserRole domain);

  @Override
  List<UserRole> toDomain(List<UserRoleEntity> entities);

  @Override
  List<UserRoleEntity> toEntity(List<UserRole> domains);
}
```

- [ ] **Step 4: Tạo port**

```java
package com.vandunxg.file_processing.auth.application.port.out;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.vandunxg.file_processing.auth.domain.model.UserRole;

public interface UserRoleRepositoryPort {

  List<UserRole> findByUserId(UUID userId);

  List<UUID> findUserIdsByRoleId(UUID roleId);

  void saveAll(Collection<UserRole> assignments);

  long countByRoleId(UUID roleId);
}
```

- [ ] **Step 5: Tạo adapter**

```java
package com.vandunxg.file_processing.auth.adapter.out.persistence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.JpaUserRoleRepository;
import com.vandunxg.file_processing.auth.adapter.out.persistence.mapper.UserRolePersistenceMapper;
import com.vandunxg.file_processing.auth.application.port.out.UserRoleRepositoryPort;
import com.vandunxg.file_processing.auth.domain.model.UserRole;
import lombok.RequiredArgsConstructor;

@Repository
@RequiredArgsConstructor
public class UserRolePersistenceAdapter implements UserRoleRepositoryPort {

  private final JpaUserRoleRepository jpa;
  private final UserRolePersistenceMapper mapper;

  @Override
  @Transactional(readOnly = true)
  public List<UserRole> findByUserId(UUID userId) {
    return mapper.toDomain(jpa.findActiveByUserId(userId));
  }

  @Override
  @Transactional(readOnly = true)
  public List<UUID> findUserIdsByRoleId(UUID roleId) {
    return jpa.findUserIdsByRoleId(roleId);
  }

  @Override
  @Transactional
  public void saveAll(Collection<UserRole> assignments) {
    if (assignments == null || assignments.isEmpty()) return;
    jpa.saveAll(mapper.toEntity(new ArrayList<>(assignments)));
  }

  @Override
  @Transactional(readOnly = true)
  public long countByRoleId(UUID roleId) {
    return jpa.countByRoleIdAndDeletedAtIsNull(roleId);
  }
}
```

- [ ] **Step 6: Build + commit**

```bash
./mvnw -DskipTests clean compile
git add src/main/java/com/vandunxg/file_processing/auth/adapter/out/persistence/entity/UserRoleEntity.java src/main/java/com/vandunxg/file_processing/auth/adapter/out/persistence/entity/JpaUserRoleRepository.java src/main/java/com/vandunxg/file_processing/auth/adapter/out/persistence/mapper/UserRolePersistenceMapper.java src/main/java/com/vandunxg/file_processing/auth/application/port/out/UserRoleRepositoryPort.java src/main/java/com/vandunxg/file_processing/auth/adapter/out/persistence/UserRolePersistenceAdapter.java
git commit -m "feat(auth): UserRole persistence — assignment table for user↔role N:M"
```

---

## Task 26: `PasswordHasherPort` + `BcryptPasswordHasherAdapter`

**Files:**
- Create: `auth/application/port/out/PasswordHasherPort.java`
- Create: `auth/adapter/out/password/BcryptPasswordHasherAdapter.java`

**Interfaces:**
- Consumes: `AuthProperties.password.bcryptCost` (Task 28).
- Produces:
  - `PasswordHasherPort`:
    - `String hash(String rawPassword)` — trả `{bcrypt}$2a$12$...`
    - `boolean matches(String rawPassword, String storedHash)`
    - `boolean needsUpgrade(String storedHash)` — return true nếu cost hiện tại khác cost cấu hình

- [ ] **Step 1: Tạo `PasswordHasherPort.java`**

```java
package com.vandunxg.file_processing.auth.application.port.out;

public interface PasswordHasherPort {

  String hash(String rawPassword);

  boolean matches(String rawPassword, String storedHash);

  boolean needsUpgrade(String storedHash);
}
```

- [ ] **Step 2: Tạo `BcryptPasswordHasherAdapter.java`**

```java
package com.vandunxg.file_processing.auth.adapter.out.password;

import java.util.HashMap;
import java.util.Map;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import com.vandunxg.file_processing.auth.application.port.out.PasswordHasherPort;
import com.vandunxg.file_processing.auth.configuration.AuthProperties;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j(topic = "AUTH-PASSWORD")
public class BcryptPasswordHasherAdapter implements PasswordHasherPort {

  private static final String CURRENT_ID = "bcrypt";

  private final int cost;
  private final PasswordEncoder delegating;

  public BcryptPasswordHasherAdapter(AuthProperties properties) {
    this.cost = properties.password().bcryptCost();
    Map<String, PasswordEncoder> encoders = new HashMap<>();
    encoders.put(CURRENT_ID, new BCryptPasswordEncoder(this.cost));
    this.delegating = new DelegatingPasswordEncoder(CURRENT_ID, encoders);
  }

  @Override
  public String hash(String rawPassword) {
    if (rawPassword == null || rawPassword.isEmpty()) {
      throw new IllegalArgumentException("Password must not be null or empty");
    }
    return delegating.encode(rawPassword);
  }

  @Override
  public boolean matches(String rawPassword, String storedHash) {
    if (rawPassword == null || storedHash == null) return false;
    try {
      return delegating.matches(rawPassword, storedHash);
    } catch (IllegalArgumentException e) {
      log.warn("[matches] invalid stored hash format — treating as no match");
      return false;
    }
  }

  @Override
  public boolean needsUpgrade(String storedHash) {
    if (storedHash == null) return true;
    if (!storedHash.startsWith("{bcrypt}$2")) return true;
    // detect cost embedded in hash: {bcrypt}$2a$COST$...
    try {
      String stripped = storedHash.substring("{bcrypt}$2a$".length());
      int idx = stripped.indexOf('$');
      if (idx < 0) return true;
      int hashCost = Integer.parseInt(stripped.substring(0, idx));
      return hashCost != this.cost;
    } catch (RuntimeException e) {
      log.warn("[needsUpgrade] cannot parse hash cost, treating as needs upgrade");
      return true;
    }
  }
}
```

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/vandunxg/file_processing/auth/application/port/out/PasswordHasherPort.java src/main/java/com/vandunxg/file_processing/auth/adapter/out/password/BcryptPasswordHasherAdapter.java
git commit -m "feat(auth): PasswordHasherPort + BCrypt adapter with DelegatingPasswordEncoder

- {bcrypt} prefix format for future algorithm upgrade (RULE.md-compatible)
- cost read from AuthProperties.password.bcryptCost (dev/prod 12, test 4)
- needsUpgrade() parses embedded cost from stored hash, triggers rehash-on-login when policy cost differs
- matches() gracefully handles invalid hash format"
```

---

## Task 27: `ClockPort` + `IdGeneratorPort` + adapters

**Files:**
- Create: `auth/application/port/out/ClockPort.java`
- Create: `auth/application/port/out/IdGeneratorPort.java`
- Create: `auth/adapter/out/system/SystemClockAdapter.java`
- Create: `auth/adapter/out/system/UuidIdGeneratorAdapter.java`

**Interfaces:**
- Produces:
  - `ClockPort.now(): Instant` — testable time source (bằng fake trong test).
  - `IdGeneratorPort.nextId(): UUID` — wraps `IdUtils.nextId()`.

- [ ] **Step 1: `ClockPort.java`**

```java
package com.vandunxg.file_processing.auth.application.port.out;

import java.time.Instant;

public interface ClockPort {

  Instant now();
}
```

- [ ] **Step 2: `IdGeneratorPort.java`**

```java
package com.vandunxg.file_processing.auth.application.port.out;

import java.util.UUID;

public interface IdGeneratorPort {

  UUID nextId();
}
```

- [ ] **Step 3: `SystemClockAdapter.java`**

```java
package com.vandunxg.file_processing.auth.adapter.out.system;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Component;

import com.vandunxg.file_processing.auth.application.port.out.ClockPort;

@Component
public class SystemClockAdapter implements ClockPort {

  private final Clock clock = Clock.systemUTC();

  @Override
  public Instant now() {
    return Instant.now(clock);
  }
}
```

- [ ] **Step 4: `UuidIdGeneratorAdapter.java`**

```java
package com.vandunxg.file_processing.auth.adapter.out.system;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.vandunxg.common.utils.IdUtils;
import com.vandunxg.file_processing.auth.application.port.out.IdGeneratorPort;

@Component
public class UuidIdGeneratorAdapter implements IdGeneratorPort {

  @Override
  public UUID nextId() {
    return IdUtils.nextId();
  }
}
```

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/vandunxg/file_processing/auth/application/port/out/ClockPort.java src/main/java/com/vandunxg/file_processing/auth/application/port/out/IdGeneratorPort.java src/main/java/com/vandunxg/file_processing/auth/adapter/out/system/SystemClockAdapter.java src/main/java/com/vandunxg/file_processing/auth/adapter/out/system/UuidIdGeneratorAdapter.java
git commit -m "feat(auth): ClockPort + IdGeneratorPort + system adapters

- ClockPort abstracts time source (fake in unit tests)
- IdGeneratorPort wraps IdUtils.nextId() from common-utils
- system adapters are @Component beans injected via constructor"
```

---

## Task 28: `AuthProperties` typed config + `AuthPersistenceConfiguration`

**Files:**
- Create: `auth/configuration/AuthProperties.java`
- Create: `auth/configuration/AuthPersistenceConfiguration.java`

**Interfaces:**
- Produces: `@ConfigurationProperties(prefix = "app.auth")` record binding namespace từ Task 3.

- [ ] **Step 1: `AuthProperties.java`**

```java
package com.vandunxg.file_processing.auth.configuration;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.auth")
public record AuthProperties(
    Jwt jwt,
    Password password,
    Login login,
    Bootstrap bootstrap,
    Cors cors) {

  public record Jwt(
      String issuer,
      String audience,
      Duration accessTokenTtl,
      Duration passwordChangeTokenTtl,
      Duration clockSkew,
      String activeKid,
      String privateKeyPemBase64,
      List<PublicKeyEntry> publicKeys) {}

  public record PublicKeyEntry(String kid, String pemBase64) {}

  public record Password(String encoder, int bcryptCost, int minLength, int maxLength) {}

  public record Login(int maxFailures, Duration failureWindow, Duration lockDuration) {}

  public record Bootstrap(Admin admin) {
    public record Admin(
        boolean enabled,
        String username,
        String email,
        String password,
        String displayName) {}
  }

  public record Cors(
      List<String> allowedOrigins,
      List<String> allowedMethods,
      List<String> allowedHeaders,
      boolean allowCredentials,
      long maxAge) {}
}
```

- [ ] **Step 2: `AuthPersistenceConfiguration.java`**

```java
package com.vandunxg.file_processing.auth.configuration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@Configuration
@EnableJpaAuditing(auditorAwareRef = "springSecurityAuditorAware")
@EnableJpaRepositories(basePackages = "com.vandunxg.file_processing.auth.adapter.out.persistence.entity")
@EnableConfigurationProperties(AuthProperties.class)
public class AuthPersistenceConfiguration {}
```

- [ ] **Step 3: Build + commit**

```bash
./mvnw -DskipTests clean compile
git add src/main/java/com/vandunxg/file_processing/auth/configuration/AuthProperties.java src/main/java/com/vandunxg/file_processing/auth/configuration/AuthPersistenceConfiguration.java
git commit -m "feat(auth): AuthProperties typed record + JPA auditing/repositories configuration

- record-based @ConfigurationProperties for immutability + concise definition
- nested records: Jwt, Password, Login, Bootstrap.Admin, Cors, PublicKeyEntry
- durations parsed as ISO-8601 (PT15M, PT60S, P30D)
- AuthPersistenceConfiguration enables JPA auditing (using SpringSecurityAuditorAware bean from common-web SecurityConfiguration) and JPA repositories scoped to auth adapter package"
```

---

## Task 29: IT — `MigrationAndSeedIT` verify migration đúng, seed đủ

**Files:**
- Create: `src/test/java/com/vandunxg/file_processing/auth/adapter/out/persistence/MigrationAndSeedIT.java`

**Interfaces:**
- Consumes: `PostgresTestContainerBase`, `JpaRoleRepository`, `JpaRolePermissionRepository`.

- [ ] **Step 1: Viết IT**

```java
package com.vandunxg.file_processing.auth.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.vandunxg.common.models.enums.Action;
import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.JpaRolePermissionRepository;
import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.JpaRoleRepository;
import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.RoleEntity;
import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.RolePermissionEntity;
import com.vandunxg.file_processing.auth.domain.model.ActiveStatus;
import com.vandunxg.file_processing.testsupport.PostgresIntegrationTest;
import com.vandunxg.file_processing.testsupport.PostgresTestContainerBase;

@PostgresIntegrationTest
class MigrationAndSeedIT extends PostgresTestContainerBase {

  private static final UUID ADMIN_ROLE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID OPERATOR_ROLE_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");

  @Autowired JpaRoleRepository roleRepository;
  @Autowired JpaRolePermissionRepository rolePermissionRepository;

  @Test
  void migrationSeedsTwoBuiltInRoles() {
    List<RoleEntity> all = roleRepository.findAll();
    assertThat(all).hasSize(2);

    RoleEntity admin = roleRepository.findById(ADMIN_ROLE_ID).orElseThrow();
    assertThat(admin.getCode()).isEqualTo("ADMIN");
    assertThat(admin.getIsConst()).isTrue();
    assertThat(admin.getStatus()).isEqualTo(ActiveStatus.ACTIVE);
    assertThat(admin.getDeletedAt()).isNull();

    RoleEntity operator = roleRepository.findById(OPERATOR_ROLE_ID).orElseThrow();
    assertThat(operator.getCode()).isEqualTo("OPERATOR");
    assertThat(operator.getIsConst()).isTrue();
  }

  @Test
  void adminHasOnlyAllManagePermission() {
    List<RolePermissionEntity> perms = rolePermissionRepository.findActiveByRoleId(ADMIN_ROLE_ID);
    assertThat(perms).hasSize(1);
    assertThat(perms.get(0).getResourceCode()).isEqualTo("ALL");
    assertThat(perms.get(0).getAction()).isEqualTo(Action.MANAGE);
  }

  @Test
  void operatorHasSelfPermissions() {
    List<RolePermissionEntity> perms = rolePermissionRepository.findActiveByRoleId(OPERATOR_ROLE_ID);
    assertThat(perms).hasSize(10);
    assertThat(perms).extracting(RolePermissionEntity::getResourceCode)
        .containsExactlyInAnyOrder(
            "FILE", "FILE", "FILE",
            "JOB", "JOB",
            "REPORT",
            "USER", "USER",
            "SESSION", "SESSION");
    assertThat(perms).extracting(RolePermissionEntity::getAction)
        .allMatch(a -> a.name().startsWith("SELF_"));
  }
}
```

- [ ] **Step 2: Run IT**

```bash
./mvnw -Dtest=MigrationAndSeedIT test
```
Expected: `Tests run: 3, Failures: 0`.

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/vandunxg/file_processing/auth/adapter/out/persistence/MigrationAndSeedIT.java
git commit -m "test(auth): IT verifies migration seeds 2 built-in roles with correct permissions

- ADMIN has exactly (ALL, MANAGE)
- OPERATOR has 10 SELF_* permissions on FILE/JOB/REPORT/USER/SESSION
- Testcontainers PostgreSQL 16 shared across test classes"
```

---

## Task 30: IT — `LastActiveAdminQueryIT` verify concurrency-safe count

**Files:**
- Create: `src/test/java/com/vandunxg/file_processing/auth/adapter/out/persistence/LastActiveAdminQueryIT.java`

- [ ] **Step 1: Viết IT**

```java
package com.vandunxg.file_processing.auth.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.JpaUserRepository;
import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.JpaUserRoleRepository;
import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.UserEntity;
import com.vandunxg.file_processing.auth.adapter.out.persistence.entity.UserRoleEntity;
import com.vandunxg.file_processing.auth.domain.model.UserStatus;
import com.vandunxg.file_processing.testsupport.PostgresIntegrationTest;
import com.vandunxg.file_processing.testsupport.PostgresTestContainerBase;

@PostgresIntegrationTest
class LastActiveAdminQueryIT extends PostgresTestContainerBase {

  private static final UUID ADMIN_ROLE_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

  @Autowired JpaUserRepository userRepo;
  @Autowired JpaUserRoleRepository userRoleRepo;

  private UserEntity insertActiveAdmin(String username, String email) {
    var enc = new BCryptPasswordEncoder(4);
    var u = UserEntity.builder()
        .id(UUID.randomUUID())
        .username(username)
        .normalizedUsername(username.toLowerCase())
        .email(email)
        .normalizedEmail(email.toLowerCase())
        .displayName(username)
        .passwordHash("{bcrypt}" + enc.encode("Passw0rd!Test"))
        .status(UserStatus.ACTIVE)
        .mustChangePassword(false)
        .failedLoginCount(0)
        .credentialVersion(1)
        .passwordChangedAt(Instant.now())
        .emailVerifiedAt(Instant.now())
        .build();
    userRepo.save(u);
    userRoleRepo.save(UserRoleEntity.builder()
        .id(UUID.randomUUID())
        .userId(u.getId())
        .roleId(ADMIN_ROLE_ID)
        .build());
    return u;
  }

  @Test
  void countActiveAdmins_returnsZero_initially() {
    // Migration seeds roles nhưng chưa có user nào
    assertThat(userRepo.countActiveAdmins()).isZero();
  }

  @Test
  void countActiveAdmins_increments_asAdminsAdded() {
    insertActiveAdmin("admin1", "admin1@example.com");
    assertThat(userRepo.countActiveAdmins()).isEqualTo(1);
    insertActiveAdmin("admin2", "admin2@example.com");
    assertThat(userRepo.countActiveAdmins()).isEqualTo(2);
  }

  @Test
  void countActiveAdmins_excludes_softDeletedUser() {
    UserEntity admin = insertActiveAdmin("admin3", "admin3@example.com");
    admin.setDeletedAt(Instant.now());
    userRepo.save(admin);
    assertThat(userRepo.countActiveAdmins()).isZero();
  }

  @Test
  void countActiveAdmins_excludes_disabledUser() {
    UserEntity admin = insertActiveAdmin("admin4", "admin4@example.com");
    admin.setStatus(UserStatus.DISABLED);
    userRepo.save(admin);
    assertThat(userRepo.countActiveAdmins()).isZero();
  }

  @Test
  void countActiveAdmins_excludes_softDeletedUserRole() {
    UserEntity admin = insertActiveAdmin("admin5", "admin5@example.com");
    userRoleRepo.findActiveByUserId(admin.getId()).forEach(ur -> {
      ur.setDeletedAt(Instant.now());
      userRoleRepo.save(ur);
    });
    assertThat(userRepo.countActiveAdmins()).isZero();
  }
}
```

- [ ] **Step 2: Run + commit**

```bash
./mvnw -Dtest=LastActiveAdminQueryIT test
git add src/test/java/com/vandunxg/file_processing/auth/adapter/out/persistence/LastActiveAdminQueryIT.java
git commit -m "test(auth): IT verifies countActiveAdmins query excludes soft-deleted, disabled, or role-revoked users

- 5 scenarios covering the last-active-admin invariant boundary
- verifies query joins user_role + role and filters deleted_at IS NULL correctly
- foundation for LastActiveAdminPolicy usage in Phase 5 admin management"
```

---

## Kết thúc Part 2b

Sau khi hoàn thành Task 22-30, bạn có:
- Full persistence adapter cho User, Role, RolePermission, UserRole, AuditLog.
- `PasswordHasherPort` + BCrypt adapter với upgrade detection.
- `ClockPort` + `IdGeneratorPort` + adapter.
- `AuthProperties` typed record với 5 nested config namespace.
- `AuthPersistenceConfiguration` bật JPA auditing + repository scan.
- 2 IT: migration+seed verification, last-active-admin query.

**Chuyển sang Part 3** (`2026-07-17-auth-part3-jwt-and-login.md`) — JWT infrastructure + AuthorityService + LoginUseCase + BootstrapAdmin.
