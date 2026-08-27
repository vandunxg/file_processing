# Import File Model Implementation Plan

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
> <sub>Original agent instruction, retained verbatim for the record: **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.</sub>

**Goal:** Add the minimal File Import domain and JPA model needed to register uploaded file metadata before processing jobs exist.

**Architecture:** Follow the existing auth module pattern: pure domain model, JPA entity, MapStruct mapper, and Flyway migration. Keep upload use-case, repository ports, processing jobs, attempts, customers, retry, cancellation, and reports out of this scope.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring Data JPA, Flyway, PostgreSQL, Lombok, MapStruct 1.6.3, JUnit 5, AssertJ, Maven.

## Global Constraints

- Implement only `ImportFile`; do not add `ProcessingJob`, `ProcessingAttempt`, customer upsert, upload controller, storage adapter, retry, cancellation, recovery, or report download.
- Use a system-generated `storageKey`; never derive the storage path directly from a user-supplied filename.
- Keep the original filename only for display.
- Enforce lowercase, 64-character SHA-256 checksum.
- Enforce logical uniqueness on `(ownerId, checksumSha256)` in PostgreSQL.
- Create metadata only after object storage succeeds; this plan only creates the model that supports that later flow.
- Never mutate registered import-file content metadata after creation.
- Do not add dependencies.
- Do not create commits unless the user explicitly requests them.

---

## File Structure

| File                                                                                                                    | Responsibility                                                                |
|-------------------------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------|
| `src/main/java/com/vandunxg/file_processing/fileimport/domain/model/ImportFile.java`                                    | Pure domain aggregate for immutable registered upload metadata.               |
| `src/test/java/com/vandunxg/file_processing/fileimport/domain/model/ImportFileTest.java`                                | Unit tests for construction invariants.                                       |
| `src/main/java/com/vandunxg/file_processing/fileimport/adapter/out/persistence/entity/ImportFileEntity.java`            | JPA mapping for `import_files`.                                               |
| `src/main/java/com/vandunxg/file_processing/fileimport/adapter/out/persistence/mapper/ImportFilePersistenceMapper.java` | MapStruct domain/entity conversion.                                           |
| `src/main/resources/db/migration/V202607280000__create_import_files.sql`                                                | PostgreSQL table, checks, unique constraints, and indexes.                    |
| `src/test/java/com/vandunxg/file_processing/auth/adapter/out/persistence/MigrationAndSeedIT.java`                       | Existing migration smoke test extended with `import_files` schema assertions. |

### Task 1: Add The Pure `ImportFile` Domain Model

**Files:**

- Create: `src/main/java/com/vandunxg/file_processing/fileimport/domain/model/ImportFile.java`
- Create: `src/test/java/com/vandunxg/file_processing/fileimport/domain/model/ImportFileTest.java`

**Interfaces:**

- Consumes: `com.vandunxg.common.models.domain.AuditableDomain`, `com.vandunxg.common.utils.IdUtils`.
- Produces: `ImportFile.register(UUID ownerId, String originalFilename, String storageKey, String checksumSha256, long sizeBytes, String detectedContentType, Instant retentionDeadline): ImportFile`.

- [ ] **Step 1: Write the failing domain test**

  Create `ImportFileTest.java`:

  ```java
  package com.vandunxg.file_processing.fileimport.domain.model;

  import static org.assertj.core.api.Assertions.assertThat;
  import static org.assertj.core.api.Assertions.assertThatThrownBy;

  import java.time.Instant;
  import java.util.UUID;

  import org.junit.jupiter.api.Test;

  class ImportFileTest {

    private static final String CHECKSUM = "a".repeat(64);
    private static final Instant RETENTION_DEADLINE = Instant.parse("2026-08-27T00:00:00Z");

    @Test
    void registerCreatesStoredFileMetadata() {
      UUID ownerId = UUID.randomUUID();

      FileImport file =
          FileImport.register(
              ownerId,
              " customers.csv ",
              "imports/2026/07/file.csv",
              CHECKSUM,
              123L,
              "text/csv",
              RETENTION_DEADLINE);

      assertThat(file.getId()).isNotNull();
      assertThat(file.getOwnerId()).isEqualTo(ownerId);
      assertThat(file.getOriginalFilename()).isEqualTo("customers.csv");
      assertThat(file.getStorageKey()).isEqualTo("imports/2026/07/file.csv");
      assertThat(file.getChecksumSha256()).isEqualTo(CHECKSUM);
      assertThat(file.getSizeBytes()).isEqualTo(123L);
      assertThat(file.getDetectedContentType()).isEqualTo("text/csv");
      assertThat(file.getRetentionDeadline()).isEqualTo(RETENTION_DEADLINE);
    }

    @Test
    void registerRejectsInvalidChecksum() {
      assertThatThrownBy(
              () ->
                  FileImport.register(
                      UUID.randomUUID(),
                      "customers.csv",
                      "imports/file.csv",
                      "ABC",
                      1L,
                      "text/csv",
                      RETENTION_DEADLINE))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("checksum");
    }

    @Test
    void registerRejectsMissingCoreFields() {
      assertThatThrownBy(
              () ->
                  FileImport.register(
                      UUID.randomUUID(),
                      " ",
                      "imports/file.csv",
                      CHECKSUM,
                      1L,
                      "text/csv",
                      RETENTION_DEADLINE))
          .isInstanceOf(IllegalArgumentException.class);

      assertThatThrownBy(
              () ->
                  FileImport.register(
                      UUID.randomUUID(),
                      "customers.csv",
                      " ",
                      CHECKSUM,
                      1L,
                      "text/csv",
                      RETENTION_DEADLINE))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void registerRejectsNegativeSize() {
      assertThatThrownBy(
              () ->
                  FileImport.register(
                      UUID.randomUUID(),
                      "customers.csv",
                      "imports/file.csv",
                      CHECKSUM,
                      -1L,
                      "text/csv",
                      RETENTION_DEADLINE))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("size");
    }
  }
  ```

- [ ] **Step 2: Run the focused test to verify it fails**

  Run: `./mvnw -Dtest=ImportFileTest test`

  Expected: compilation failure because `ImportFile` does not exist.

- [ ] **Step 3: Add the minimal domain model**

  Create `ImportFile.java`:

  ```java
  package com.vandunxg.file_processing.fileimport.domain.model;

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
  public class ImportFile extends AuditableDomain {

    private UUID id;
    private UUID ownerId;
    private String originalFilename;
    private String storageKey;
    private String checksumSha256;
    private long sizeBytes;
    private String detectedContentType;
    private Instant retentionDeadline;
    private Long version;

    public static ImportFile register(
        UUID ownerId,
        String originalFilename,
        String storageKey,
        String checksumSha256,
        long sizeBytes,
        String detectedContentType,
        Instant retentionDeadline) {
      if (ownerId == null
          || isBlank(originalFilename)
          || isBlank(storageKey)
          || isBlank(detectedContentType)
          || retentionDeadline == null) {
        throw new IllegalArgumentException("Import file metadata is required");
      }
      if (checksumSha256 == null || !checksumSha256.matches("[0-9a-f]{64}")) {
        throw new IllegalArgumentException("Import file checksum must be lowercase SHA-256 hex");
      }
      if (sizeBytes < 0) {
        throw new IllegalArgumentException("Import file size must be non-negative");
      }
      return ImportFile.builder()
          .id(IdUtils.nextId())
          .ownerId(ownerId)
          .originalFilename(originalFilename.trim())
          .storageKey(storageKey.trim())
          .checksumSha256(checksumSha256)
          .sizeBytes(sizeBytes)
          .detectedContentType(detectedContentType.trim())
          .retentionDeadline(retentionDeadline)
          .build();
    }

    private static boolean isBlank(String value) {
      return value == null || value.isBlank();
    }
  }
  ```

- [ ] **Step 4: Run the focused test to verify it passes**

  Run: `./mvnw -Dtest=ImportFileTest test`

  Expected: `Tests run: 4, Failures: 0, Errors: 0`.

### Task 2: Add JPA Entity, Mapper, Migration, And Schema Checks

**Files:**

- Create: `src/main/java/com/vandunxg/file_processing/fileimport/adapter/out/persistence/entity/ImportFileEntity.java`
- Create: `src/main/java/com/vandunxg/file_processing/fileimport/adapter/out/persistence/mapper/ImportFilePersistenceMapper.java`
- Create: `src/main/resources/db/migration/V202607280000__create_import_files.sql`
- Modify: `src/test/java/com/vandunxg/file_processing/auth/adapter/out/persistence/MigrationAndSeedIT.java`

**Interfaces:**

- Consumes: `ImportFile` from Task 1.
- Produces: `ImportFileEntity` mapped to table `import_files`; `ImportFilePersistenceMapper extends EntityMapper<ImportFile, ImportFileEntity>`.

- [ ] **Step 1: Extend migration integration tests first**

  Add this test method to `MigrationAndSeedIT.java`:

  ```java
  @Test
  @Transactional
  void migrations_createImportFilesWithDuplicateAndIntegrityGuards() {
    UUID ownerId = UUID.randomUUID();
    Instant now = Instant.now();
    String checksum = "b".repeat(64);

    jdbcTemplate.update(
        "INSERT INTO import_files (id, owner_id, original_filename, storage_key, checksum_sha256, "
            + "size_bytes, detected_content_type, retention_deadline, created_at, last_modified_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        UUID.randomUUID(),
        ownerId,
        "customers.csv",
        "imports/one.csv",
        checksum,
        12L,
        "text/csv",
        Timestamp.from(now.plusSeconds(30 * 24 * 60 * 60)),
        Timestamp.from(now),
        Timestamp.from(now));

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "INSERT INTO import_files (id, owner_id, original_filename, storage_key, "
                        + "checksum_sha256, size_bytes, detected_content_type, retention_deadline, "
                        + "created_at, last_modified_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(),
                    ownerId,
                    "copy.csv",
                    "imports/two.csv",
                    checksum,
                    12L,
                    "text/csv",
                    Timestamp.from(now.plusSeconds(30 * 24 * 60 * 60)),
                    Timestamp.from(now),
                    Timestamp.from(now)))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThatThrownBy(
            () ->
                jdbcTemplate.update(
                    "INSERT INTO import_files (id, owner_id, original_filename, storage_key, "
                        + "checksum_sha256, size_bytes, detected_content_type, retention_deadline, "
                        + "created_at, last_modified_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "bad.csv",
                    "imports/bad.csv",
                    "ABC",
                    -1L,
                    "text/csv",
                    Timestamp.from(now.plusSeconds(30 * 24 * 60 * 60)),
                    Timestamp.from(now),
                    Timestamp.from(now)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }
  ```

  Add this index-shape test method to the same class:

  ```java
  @Test
  void migrations_indexImportFilesForOwnerListingAndRetentionCleanup() {
    List<String> indexes =
        jdbcTemplate.queryForList(
            "SELECT indexname FROM pg_indexes WHERE schemaname = 'public' "
                + "AND indexname IN ('import_files_owner_checksum_uk', "
                + "'import_files_storage_key_uk', 'import_files_owner_created_at_idx', "
                + "'import_files_retention_deadline_idx')",
            String.class);

    assertThat(indexes)
        .containsExactlyInAnyOrder(
            "import_files_owner_checksum_uk",
            "import_files_storage_key_uk",
            "import_files_owner_created_at_idx",
            "import_files_retention_deadline_idx");
  }
  ```

- [ ] **Step 2: Run migration tests to verify they fail before DDL exists**

  Run: `./mvnw -Dtest=MigrationAndSeedIT test`

  Expected: failure mentioning relation `import_files` does not exist, or missing expected indexes.

- [ ] **Step 3: Add the Flyway migration**

  Create `V202607280000__create_import_files.sql`:

  ```sql
  CREATE TABLE import_files (
      id                    UUID PRIMARY KEY,
      owner_id              UUID          NOT NULL,
      original_filename     VARCHAR(255)  NOT NULL,
      storage_key           VARCHAR(512)  NOT NULL,
      checksum_sha256       VARCHAR(64)   NOT NULL,
      size_bytes            BIGINT        NOT NULL,
      detected_content_type VARCHAR(100)  NOT NULL,
      retention_deadline    TIMESTAMPTZ   NOT NULL,
      created_by            VARCHAR(100),
      created_at            TIMESTAMPTZ   NOT NULL,
      last_modified_by      VARCHAR(100),
      last_modified_at      TIMESTAMPTZ   NOT NULL,
      version               BIGINT        NOT NULL DEFAULT 0,

      CONSTRAINT import_files_checksum_sha256_chk
          CHECK (checksum_sha256 ~ '^[0-9a-f]{64}$'),
      CONSTRAINT import_files_size_bytes_chk
          CHECK (size_bytes >= 0)
  );

  CREATE UNIQUE INDEX import_files_owner_checksum_uk
      ON import_files (owner_id, checksum_sha256);

  CREATE UNIQUE INDEX import_files_storage_key_uk
      ON import_files (storage_key);

  CREATE INDEX import_files_owner_created_at_idx
      ON import_files (owner_id, created_at DESC);

  CREATE INDEX import_files_retention_deadline_idx
      ON import_files (retention_deadline);
  ```

- [ ] **Step 4: Add the JPA entity**

  Create `ImportFileEntity.java`:

  ```java
  package com.vandunxg.file_processing.fileimport.adapter.out.persistence.entity;

  import java.time.Instant;
  import java.util.UUID;

  import com.vandunxg.common.models.entities.AuditableEntity;
  import jakarta.persistence.Column;
  import jakarta.persistence.Entity;
  import jakarta.persistence.Id;
  import jakarta.persistence.Table;
  import jakarta.persistence.Version;
  import lombok.AllArgsConstructor;
  import lombok.EqualsAndHashCode;
  import lombok.Getter;
  import lombok.NoArgsConstructor;
  import lombok.Setter;
  import lombok.ToString;

  @Entity
  @Table(name = "import_files")
  @Getter
  @Setter
  @ToString
  @NoArgsConstructor
  @AllArgsConstructor
  @EqualsAndHashCode(callSuper = false, of = "id")
  public class ImportFileEntity extends AuditableEntity {

    @Id
    @Column(name = "id")
    private UUID id;

    @Column(name = "owner_id", nullable = false)
    private UUID ownerId;

    @Column(name = "original_filename", nullable = false, length = 255)
    private String originalFilename;

    @Column(name = "storage_key", nullable = false, length = 512)
    private String storageKey;

    @Column(name = "checksum_sha256", nullable = false, length = 64)
    private String checksumSha256;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "detected_content_type", nullable = false, length = 100)
    private String detectedContentType;

    @Column(name = "retention_deadline", nullable = false)
    private Instant retentionDeadline;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;
  }
  ```

- [ ] **Step 5: Add the persistence mapper**

  Create `ImportFilePersistenceMapper.java`:

  ```java
  package com.vandunxg.file_processing.fileimport.adapter.out.persistence.mapper;

  import java.util.List;

  import com.vandunxg.common.models.mapper.EntityMapper;
  import com.vandunxg.file_processing.fileimport.adapter.out.persistence.entity.ImportFileEntity;
  import com.vandunxg.file_processing.fileimport.domain.model.FileImport;
  import org.mapstruct.Mapper;
  import org.mapstruct.Mapping;
  import org.mapstruct.MappingConstants;
  import org.mapstruct.ReportingPolicy;

  @Mapper(
      componentModel = MappingConstants.ComponentModel.SPRING,
      unmappedTargetPolicy = ReportingPolicy.ERROR,
      unmappedSourcePolicy = ReportingPolicy.WARN)
  public interface ImportFilePersistenceMapper extends EntityMapper<FileImport, ImportFileEntity> {

    @Override
    FileImport toDomain(ImportFileEntity entity);

    @Override
    @Mapping(target = "createdAt", ignore = true)
    @Mapping(target = "lastModifiedAt", ignore = true)
    @Mapping(target = "createdBy", ignore = true)
    @Mapping(target = "lastModifiedBy", ignore = true)
    ImportFileEntity toEntity(FileImport domain);

    @Override
    List<FileImport> toDomain(List<ImportFileEntity> entities);

    @Override
    List<ImportFileEntity> toEntity(List<FileImport> domains);
  }
  ```

- [ ] **Step 6: Run focused tests**

  Run: `./mvnw -Dtest=ImportFileTest,MigrationAndSeedIT test`

  Expected: domain tests pass and migration tests pass.

- [ ] **Step 7: Run compile to prove MapStruct generates the mapper**

  Run: `./mvnw test -DskipTests`

  Expected: build succeeds and MapStruct reports no unmapped target errors.

## Self-Review Notes

- Spec coverage: Task 1 covers the domain invariants; Task 2 covers JPA mapping, database uniqueness, checksum/size checks, and retention/listing indexes.
- Scope intentionally excludes repository ports/adapters and upload service because the approved scope is model-only for uploading file metadata first.
- Type consistency: all Java fields use `UUID`, `String`, `long`, `Instant`, and `Long version` consistently across domain, entity, mapper, tests, and SQL.
