# Auth Register Delivery Implementation Plan

<!-- prettier-ignore -->
> [!WARNING]
> **LEGACY ARCHITECTURE NOTICE — SUPERSEDED ARCHITECTURE GUIDANCE**
>
> Tài liệu này được tạo trước quyết định chuyển sang Pragmatic Modular DDD.
> Các package `adapter/*`, `port/*`, `*UseCase`, `*Port` và `*Adapter` trong tài
> liệu này mô tả legacy implementation và **không còn là architecture guidance**.
>
> This document predates the migration to Pragmatic Modular DDD. Every
> `adapter/in`, `adapter/out`, `port/in`, `port/out`, `*UseCase`,
> `*RepositoryPort`, and `*PersistenceAdapter` reference below records the
> legacy implementation **as it was actually built**. It is a historical record,
> not an instruction. Do not reproduce this layout, naming, or interface
> structure in new code or in a refactor.
>
> [`RULE.md`](../../../RULE.md) §4 is the source of truth for architecture. The
> business behavior, API contracts, and security requirements described here
> remain valid; only the structural guidance is superseded.

> **PLAN COMPLETED — DO NOT RE-EXECUTE.** Every task in this plan was implemented and merged. Its package layout and type names follow the legacy Hexagonal structure and are superseded by `RULE.md` §4. Kept for history only.
>
> <sub>Original agent instruction, retained verbatim for the record: **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.</sub>

**Goal:** Deliver self-service register, email verification, and resend-verification endpoints with a complete Auth domain and persistence foundation.

**Architecture:** Complete the approved Part 1 and Part 2 domain/persistence prerequisites first. Register persists the user, OPERATOR assignment, verification-token hash, and audit record atomically; the email sends only after commit. Verification atomically locks and consumes an opaque-token row before activating the user.

**Tech Stack:** Java 21, Spring Boot 4.1, PostgreSQL/Flyway, Spring Data JPA, MapStruct, BCrypt, Testcontainers PostgreSQL, Caffeine/Redis throttle, common-email.

---

### Task 1: Complete Auth Domain Foundation

**Files:** All Task 4-14 files in `2026-07-17-auth-part1-setup-domain.md`.

- [ ] Replace the staged Auth stubs with `User`, `Role`, `RolePermission`, `UserRole`, enums, `AuthErrorCode`, `AuthDomainException`, and pure policies from Part 1.
- [ ] Add the exact Part 1 domain unit tests for user state transitions, password policy, lock policy, last-active-admin policy, and permission expressions.
- [ ] Run `./mvnw -Dtest='UserTest,RoleTest,PasswordPolicyTest,LoginLockPolicyTest,LastActiveAdminPolicyTest,PermissionExpressionTest' test`.
- [ ] Commit with `feat(auth): complete domain foundation`.

### Task 2: Create Auth and Verification Schema

**Files:** All Task 15-20 Flyway migrations in `2026-07-17-auth-part2-migration-persistence.md`; create `V202607170906__create_email_verification_tokens.sql`.

- [ ] Create `auth_users`, role/RBAC, and audit tables plus deterministic ADMIN/OPERATOR seeds exactly as the Part 2 migrations specify.
- [ ] Create `auth_email_verification_tokens` with UUID primary key; user foreign key; `CHAR(64) UNIQUE token_hash`; issued, expiry, used, and IP-hash columns; indexes for active user token and expiry cleanup.
- [ ] Run the migration Testcontainers test to prove schema and role seed data.
- [ ] Commit with `feat(auth): add auth and verification schema`.

### Task 3: Implement Persistence, Security Utilities, and Typed Config

**Files:** Part 2 Task 21-30 files, plus `EmailVerificationToken` domain/entity/repository/mapper/port/adapter and typed register, resend, and verification-email properties.

- [ ] Implement separate JPA entities, repositories, MapStruct mappers, and adapters for user, role, user-role, audit, and email-verification token.
- [ ] Implement BCrypt via `DelegatingPasswordEncoder`, UTC clock, ID generator, secure opaque-token generator, SHA-256 hashing, and typed `AuthProperties`.
- [ ] Ensure token lookup for verification uses a pessimistic write lock and exposes create, consume, invalidate-by-user, and expired cleanup operations.
- [ ] Add Testcontainers tests for duplicate identity, OPERATOR lookup, token hash-only storage, expiry, and one-time consume.
- [ ] Run `./mvnw -Dtest='*PersistenceAdapterIT,*MigrationAndSeedIT,*VerificationTokenIT' test`.
- [ ] Commit with `feat(auth): add registration persistence adapters`.

### Task 4: Implement Register, Verify, and Resend Use Cases

**Files:** `RegisterUseCase`, `VerifyEmailUseCase`, `ResendVerificationEmailUseCase`; commands/results; services; email/throttle ports and adapters; audit integration.

- [ ] Write failing unit tests for valid registration, duplicate username/email, policy violation, register throttle, valid verification, expired/reused token, and enumeration-safe resend.
- [ ] Implement one registration transaction: normalize and validate, throttle IP, duplicate pre-check, hash password, create PENDING_VERIFY user with credential version one and OPERATOR role, persist only token hash, and record USER_REGISTERED.
- [ ] Register `TransactionSynchronization.afterCommit` delivery of the opaque-token verification link through `EmailSenderPort`; never log raw password or token.
- [ ] Implement verification with token-row lock, expiry/used checks, consume, user PENDING_VERIFY to ACTIVE transition, and EMAIL_VERIFIED audit.
- [ ] Implement resend by invalidating prior unused tokens, issuing a new token after commit, and returning a non-enumerating response for unknown or active accounts.
- [ ] Run isolated use-case tests with Mockito and AssertJ.
- [ ] Commit with `feat(auth): add registration and email verification use cases`.

### Task 5: Expose and Protect Public Register APIs

**Files:** web request/response DTOs, `AuthWebMapper`, `AuthController`, Security public allowlist, i18n files, OpenAPI annotations, controller integration tests.

- [ ] Add public `POST /api/v1/auth/register`, `POST /api/v1/auth/verify-email`, and `POST /api/v1/auth/resend-verification` endpoints with Jakarta validation and stable response DTOs.
- [ ] Configure only these anonymous Auth endpoints as public; keep versioned business endpoints authenticated.
- [ ] Add English and Vietnamese i18n keys for all registration, verification, and rate-limit error codes.
- [ ] Run controller Testcontainers integration tests covering 201, 409, 422, 429, 204, and 410 contracts.
- [ ] Commit with `feat(auth): expose registration and verification APIs`.

### Task 6: Verify Delivery

**Files:** All files introduced by Tasks 1-5.

- [ ] Run `./mvnw spotless:apply` only after confirming it does not reformat unrelated user-owned drafts.
- [ ] Run `./mvnw verify` with PostgreSQL Testcontainers available.
- [ ] Review staged diff, confirm no raw password/token/private key appears, and commit any verification-only correction separately.
