# Import File Model Design

## Scope

Design only the File Import model needed to register an uploaded file after the
original object is safely stored. This does not include processing jobs,
attempts, customer upsert, parsing, retry, cancellation, recovery, or report
download.

This implements the model portion of the upload/register workflow from the
approved File Import requirements, especially the `ImportFile` invariants:

- system-generated storage key
- original filename kept only for display
- lowercase 64-character SHA-256 checksum
- owner-scoped duplicate detection by `(ownerId, checksumSha256)`
- metadata created only after storage succeeds
- immutable registered file content
- retention deadline tracked for cleanup

## Approach

Use the existing project pattern:

- pure domain model under `fileimport/domain/model`
- JPA entity under `fileimport/adapter/out/persistence/entity`
- MapStruct persistence mapper under `fileimport/adapter/out/persistence/mapper`
- Flyway migration for the `import_files` table

No new abstraction is needed beyond the model, entity, mapper, and migration.
Repository ports and adapters can be added with the upload use case.

## Domain Model

Create `ImportFile` as a domain aggregate.

Fields:

- `UUID id`
- `UUID ownerId`
- `String originalFilename`
- `String storageKey`
- `String checksumSha256`
- `long sizeBytes`
- `String detectedContentType`
- `Instant retentionDeadline`
- audit fields inherited from the existing auditable domain base
- `Long version`

Factory behavior:

- require all core fields except audit/version
- reject blank filename, storage key, content type, and checksum
- require checksum to match `[0-9a-f]{64}`
- require non-negative `sizeBytes`
- require non-null retention deadline

The domain object has no method to change `storageKey`, checksum, size, content

## JPA Entity

Create `ImportFileEntity` mapped to `import_files`.

Columns:

- `id UUID PRIMARY KEY`
- `owner_id UUID NOT NULL`
- `original_filename VARCHAR(255) NOT NULL`
- `storage_key VARCHAR(512) NOT NULL`
- `checksum_sha256 VARCHAR(64) NOT NULL`
- `size_bytes BIGINT NOT NULL`
- `detected_content_type VARCHAR(100) NOT NULL`
- `retention_deadline TIMESTAMPTZ NOT NULL`
- existing audit columns: `created_by`, `created_at`, `last_modified_by`, `last_modified_at`
- `version BIGINT NOT NULL DEFAULT 0`

Constraints and indexes:

- unique `(owner_id, checksum_sha256)`
- unique `storage_key`
- check checksum format with lowercase hex
- check `size_bytes >= 0`
- index `(owner_id, created_at DESC)`
- index `retention_deadline`

## Data Flow

The upload service, when added later, will:

1. store the object with a temporary key while computing SHA-256
2. validate the file/header outside the database transaction
3. promote/copy to a generated canonical `storageKey`
4. create `ImportFile` metadata and persist it
5. rely on the unique database constraint as the final duplicate guard

This model intentionally does not represent temporary upload objects because
they are storage cleanup concerns, not registered import files.

## Error Handling

Domain construction fails fast with `IllegalArgumentException` for impossible
model state. API-level error codes such as `DUPLICATE_FILE` and
`STORAGE_UNAVAILABLE` belong to the upload application service, not this model.

Duplicate uploads are ultimately detected by the database unique constraint on
`(owner_id, checksum_sha256)` when the upload use case is implemented.

## Testing

Add focused tests during implementation:

- creates a valid `ImportFile`
- rejects invalid checksum
- rejects blank storage key or filename
- rejects negative size
- migration creates the unique constraints and indexes

No processing-job tests are part of this scope.
