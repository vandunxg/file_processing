---
name: flyway-migration
description: Use when adding or changing database schema, tables, columns, indexes, constraints, or seed data through a Flyway SQL migration.
metadata:
  version: "0.0.1"
  author: vandunxg
  scope: shared
  category: persistence
  stack: java-21, spring-boot-4
---

# Flyway migrations

- `spring.jpa.hibernate.ddl-auto` **MUST** be `validate`.
- Every schema change ships as a migration in `src/main/resources/db/migration`.
- Name: `V{yyyyMMddHHmm}__{snake_case_description}.sql`.
- Append-only: never edit a migration once merged or applied outside a
  disposable local database. Fix forward with a new migration.
- `snake_case` names; `TIMESTAMPTZ` for timestamps.
- Constraints are the final guard: `NOT NULL`, `UNIQUE`, `CHECK`, FKs.
- Index verified query patterns, not every column — but **always** index FK
  columns (PostgreSQL does not index the referencing side).
- A new `NOT NULL` column on an existing table gets a `DEFAULT` or a backfill
  in the same migration.
- Mirror enum and length rules as `CHECK (status IN (...))`.
- Name constraints and indexes explicitly; keep names code maps to errors in
  constants (see `error-handling`).

## Audit and soft-delete columns (mandatory)

**Every** `CREATE TABLE` — including join, log, token, child, and staging
tables — declares all five columns:

```sql
created_by       VARCHAR(100),
created_at       TIMESTAMPTZ  NOT NULL,
last_modified_by VARCHAR(100),
last_modified_at TIMESTAMPTZ  NOT NULL,
deleted_at       TIMESTAMPTZ  NULL      -- stays NULL if rows are never deleted
```

A migration that creates a table without them is incomplete. When altering an
older table that lacks them, add the missing columns in the same migration.

## Table template

```sql
CREATE TABLE roles
(
  id               UUID PRIMARY KEY,
  code             VARCHAR(100) NOT NULL,
  name             VARCHAR(255) NOT NULL,
  status           VARCHAR(30)  NOT NULL,
  version          BIGINT       NOT NULL DEFAULT 0,
  created_by       VARCHAR(100),
  created_at       TIMESTAMPTZ  NOT NULL,
  last_modified_by VARCHAR(100),
  last_modified_at TIMESTAMPTZ  NOT NULL,
  deleted_at       TIMESTAMPTZ  NULL
);

-- uniqueness applies to live rows only
CREATE UNIQUE INDEX roles_active_code_uidx
  ON roles (code) WHERE deleted_at IS NULL;

CREATE INDEX roles_deleted_at_idx
  ON roles (deleted_at) WHERE deleted_at IS NOT NULL;
```

Test migrations, locking, and index behavior against PostgreSQL
Testcontainers, not an in-memory database.
