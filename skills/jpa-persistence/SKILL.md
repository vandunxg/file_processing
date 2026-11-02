---
name: jpa-persistence
description: Use when writing JPA entities, Spring Data repositories, repository implementations, soft-delete logic, @Transactional boundaries, optimistic locking, or diagnosing N+1 queries, LazyInitializationException, or slow queries.
metadata:
  version: "0.0.1"
  author: vandunxg
  scope: shared
  category: persistence
  stack: java-21, spring-boot-4
---

# JPA persistence and transactions

`infrastructure/persistence` owns every database concern of a module: entities,
Spring Data interfaces, and implementations of domain repository contracts.

## Repository structure

- `domain/<Aggregate>Repository` — contract in domain types.
- `Jpa<Aggregate>Repository` — `@Repository` class implementing it, delegating
  to `<Xxx>EntityRepository` and mapping each way (see `object-mapping`). It also
  implements `application/capability/<Aggregate>SearchRepository` when a
  paginated read model exists.
- `<Xxx>EntityRepository` — Spring Data interface; **MUST NOT** be injected
  outside `infrastructure/persistence`.

## Entity rules

- Entities live in `infrastructure/persistence/entity`, never in `domain`.
- **Every** entity extends `AuditableEntity` (`created_by`, `created_at`,
  `last_modified_by`, `last_modified_at`) and maps `deletedAt` — no exceptions
  for join, log, token, or child entities.
- Table and column names in `snake_case`.
- `@Version` on aggregates that can be updated concurrently.
- Database constraints (`NOT NULL`, `UNIQUE`, `CHECK`, FKs) are the final
  correctness guard for races and uniqueness.

## Soft delete (mandatory)

- Every entity has `Instant deletedAt` ↔ column `deleted_at` (inherit it if the
  base class declares it), even when the entity is never deleted — it then
  stays `null`. Never `deleted`, `isDeleted`, or a boolean column.
- Normal deletes set `deletedAt` to the injected `Instant`; repeat deletes are
  idempotent.
- Every business read and existence check includes `deleted_at IS NULL`.
- Live-data uniqueness uses a partial unique index (see `flyway-migration`).
- Repositories exposed to application services **MUST NOT** offer `delete`,
  `deleteById`, or bulk hard delete. Physical deletion only in an explicit
  retention/maintenance job.
- Restore sets `deletedAt = null` and revalidates uniqueness and invariants.

## Transactions

- `@Transactional` on application service methods only — never controllers or
  domain. `readOnly = true` for reads.
- Keep transactions short. No HTTP, email, object-storage, or broker calls
  inside a transaction unless a consistency requirement justifies it.
- Publish after commit, or use an outbox when atomic DB-to-message consistency
  is required.
- Map optimistic-lock conflicts to `409`. Never blindly retry a
  non-idempotent transaction.
- `@Transactional`/`@Async` self-invocation through `this` bypasses the proxy.

## Optimistic locking across requests

- Hibernate ignores a `version` written onto a managed entity (including via
  `@MappingTarget`). Compare the client's `version` with the loaded aggregate
  yourself and throw the module's `409` error.
- When a response returns `version`, `saveAndFlush` before mapping back, or the
  client receives the stale value and its next write conflicts.

## Bulk updates

Bulk JPQL/SQL `UPDATE` bypasses the persistence context, `@Version`, and entity
listeners (`AuditingEntityListener` included). Set those columns yourself and
use the row count as the guard:

```java
@Modifying(flushAutomatically = true, clearAutomatically = true)
@Query("""
    UPDATE JobEntity j SET j.status = :to, j.version = j.version + 1, j.lastModifiedAt = :now
     WHERE j.id = :id AND j.status = :from AND j.deletedAt IS NULL""")
int transition(UUID id, JobStatus from, JobStatus to, Instant now); // 1 = claimed
```

## Mapping pitfalls

- IDs are assigned before persist (`IdUtils.nextId()`), which is what makes
  `@EqualsAndHashCode(of = "id")` safe. Never combine it with `@GeneratedValue`.
- With assigned IDs, `save()` issues a SELECT before INSERT unless the entity
  has a nullable `@Version Long` or implements `Persistable`.
- `@ManyToOne`/`@OneToOne` default to EAGER — declare `LAZY` explicitly.
- `@ManyToMany` as a `Set` (a `List` rewrites the whole join table).
- No `CascadeType.REMOVE`/`ALL` or `orphanRemoval`: they hard-delete. Never
  cascade from `@ManyToOne`.
- Bidirectional associations get `add*`/`remove*` helpers that sync both sides.

## Performance

- Associations `LAZY`; never `EAGER` to fix `LazyInitializationException`.
- Open Session in View disabled.
- Fix N+1 explicitly: fetch join, `@EntityGraph`, projection, or `@BatchSize`
  (or global `hibernate.default_batch_fetch_size`).
- Never paginate a collection fetch join — Hibernate pages in memory
  (HHH90003004). Set `hibernate.query.fail_on_pagination_over_collection_fetch=true`;
  page the IDs first, then fetch by IDs.
- `Slice<T>` for internal batch iteration (no COUNT query).
- Diagnose locally with `org.hibernate.SQL: DEBUG` and
  `org.hibernate.orm.jdbc.bind: TRACE` — local profile only, bind logs contain PII.
- Every list query has a bounded page size and deterministic sort.
- Index verified query patterns, not every column.
- Review generated SQL for non-trivial repository changes.
- Read models may return aggregates (the common paging base returns entities);
  build a projection only when a view is narrow and hot.
