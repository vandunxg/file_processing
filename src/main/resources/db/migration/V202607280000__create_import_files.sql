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
