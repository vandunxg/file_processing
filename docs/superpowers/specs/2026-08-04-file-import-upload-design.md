# File Import Upload Design

## Scope

Implement one authenticated upload flow only:

1. receive one multipart file;
2. stream it to Cloudflare R2;
3. persist immutable file metadata in PostgreSQL;
4. return the registered file summary.

The flow does not parse CSV content, create processing jobs, detect duplicate
uploads, retry operations, or provide download endpoints.

This covers the first upload/register slice of the File Import requirements:
stored objects use system-generated keys, metadata is created only after
storage succeeds, data is streamed, and storage credentials remain outside the
repository.

## Architecture

Use the existing module layout with a minimal hexagonal boundary:

```text
FileImportController
  -> UploadFileUseCase
  -> UploadFileService
       -> ObjectStoragePort       -> R2ObjectStorageAdapter
       -> FileImportRepositoryPort -> JpaFileImportRepositoryAdapter
```

`FileImport` remains the pure domain model. The application service owns the
workflow and has no Spring MVC or AWS SDK types in its input or output. The web
adapter maps `MultipartFile` to an input stream command. Persistence and R2
are outbound adapters.

Do not add abstractions beyond these two external boundaries.

## Upload Flow

`POST /api/v1/file-import` requires an authenticated user. The owner is the
authenticated principal; the client cannot supply it.

1. The web adapter rejects a missing or empty multipart field named `file`.
2. The use case creates an opaque `imports/{UUID}` storage key.
3. The R2 adapter streams the request body once and returns the byte count,
   SHA-256 digest, detected content type, bucket, and key.
4. The application service constructs `FileImport` with a configurable
   retention deadline and saves it through the persistence port in a database
   transaction.
5. The endpoint returns `202 Accepted` with safe metadata: id, filename, size,
   content type, checksum, creation time, and retention deadline.

The service never calls `getBytes`, `readAllBytes`, or buffers the whole file.
The original filename is never used as a storage key.

## Failure Handling

- Invalid input returns the established sanitized API error response.
- Storage failures return a sanitized storage-unavailable error and create no
  metadata.
- If metadata persistence fails after R2 upload, the service deletes the newly
  uploaded object, then rethrows the persistence failure. Cleanup failures are
  logged without sensitive file data and do not hide the original failure.
- Database uniqueness is retained in the schema for a later duplicate-upload
  slice, but this demo does not query or translate duplicate uploads yet.

## Configuration And Schema

- Use one table name consistently: `file_import`.
- Add a JPA repository and an adapter implementing the persistence port.
- Configure R2 endpoint, access key, secret key, and bucket from environment
  variables. Remove hard-coded credentials from application configuration and
  rotate the exposed credential outside this code change.
- Register configuration properties explicitly.
- Retention defaults to 30 days and remains configurable as an ISO-8601
  duration.

## Tests

Add focused tests for:

- authenticated upload streams to the object-storage port and persists the
  resulting metadata;
- generated storage keys do not include the original filename;
- storage failure saves no metadata;
- persistence failure deletes the uploaded object;
- domain invariants and persistence mapping continue to pass.

Use adapter mocks for application-service tests. No R2 integration test or CSV
processing test is in this slice.

## Explicitly Deferred

- CSV extension/header/content validation;
- SHA-256 duplicate locking and duplicate API responses;
- processing jobs and asynchronous workers;
- retries, deletion retention jobs, audit events, and download endpoints.
