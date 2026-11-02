-- Every table carries the four audit columns and deleted_at. These tables predate that rule.
-- Existing rows are backfilled with the migration time; deleted_at stays NULL where rows are never
-- soft-deleted (tokens and the staging workspace are purged physically by their cleanup jobs).

ALTER TABLE auth_email_verification_tokens
    ADD COLUMN IF NOT EXISTS created_by       VARCHAR(100),
    ADD COLUMN IF NOT EXISTS created_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS last_modified_by VARCHAR(100),
    ADD COLUMN IF NOT EXISTS last_modified_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS deleted_at       TIMESTAMPTZ;

ALTER TABLE auth_password_reset_tokens
    ADD COLUMN IF NOT EXISTS created_by       VARCHAR(100),
    ADD COLUMN IF NOT EXISTS created_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS last_modified_by VARCHAR(100),
    ADD COLUMN IF NOT EXISTS last_modified_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS deleted_at       TIMESTAMPTZ;

ALTER TABLE auth_refresh_tokens
    ADD COLUMN IF NOT EXISTS created_by       VARCHAR(100),
    ADD COLUMN IF NOT EXISTS created_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS last_modified_by VARCHAR(100),
    ADD COLUMN IF NOT EXISTS last_modified_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS deleted_at       TIMESTAMPTZ;

ALTER TABLE customer_import_staging
    ADD COLUMN IF NOT EXISTS created_by       VARCHAR(100),
    ADD COLUMN IF NOT EXISTS created_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS last_modified_by VARCHAR(100),
    ADD COLUMN IF NOT EXISTS last_modified_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS deleted_at       TIMESTAMPTZ;

ALTER TABLE customer_import_staging_issue
    ADD COLUMN IF NOT EXISTS created_by       VARCHAR(100),
    ADD COLUMN IF NOT EXISTS created_at       TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS last_modified_by VARCHAR(100),
    ADD COLUMN IF NOT EXISTS last_modified_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN IF NOT EXISTS deleted_at       TIMESTAMPTZ;

ALTER TABLE customers
    ADD COLUMN IF NOT EXISTS created_by       VARCHAR(100),
    ADD COLUMN IF NOT EXISTS last_modified_by VARCHAR(100);
