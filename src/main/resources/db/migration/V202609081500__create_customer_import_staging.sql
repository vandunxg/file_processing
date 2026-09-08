-- This is a short-lived worker workspace, not a source-of-truth domain table.  It is UNLOGGED so
-- a 1M-row import does not generate WAL solely to remember rows that can always be recreated from
-- the immutable original object.  A database restart loses the workspace; stale-job recovery then
-- marks that attempt failed and a retry rebuilds it from the original file.
CREATE UNLOGGED TABLE customer_import_staging (
    job_id                 UUID         NOT NULL,
    attempt_number         INTEGER      NOT NULL,
    row_number             BIGINT       NOT NULL,
    validation_passed      BOOLEAN      NOT NULL,
    normalized_external_id VARCHAR(64),
    normalized_full_name   VARCHAR(150),
    normalized_email       VARCHAR(254),
    normalized_phone       VARCHAR(12),
    normalized_date_of_birth DATE,
    normalized_address     VARCHAR(500),
    original_external_id   TEXT,
    original_full_name     TEXT,
    original_email         TEXT,
    original_phone         TEXT,
    original_date_of_birth TEXT,
    original_address       TEXT,
    duplicate_rank         INTEGER,

    CONSTRAINT customer_import_staging_pk PRIMARY KEY (job_id, attempt_number, row_number),
    CONSTRAINT customer_import_staging_attempt_chk CHECK (attempt_number > 0),
    CONSTRAINT customer_import_staging_row_chk CHECK (row_number > 1),
    CONSTRAINT customer_import_staging_valid_normalized_chk CHECK (
        (validation_passed AND normalized_external_id IS NOT NULL AND normalized_full_name IS NOT NULL
            AND normalized_email IS NOT NULL AND normalized_phone IS NOT NULL
            AND normalized_date_of_birth IS NOT NULL)
        OR NOT validation_passed
    )
);

CREATE INDEX customer_import_staging_dedup_idx
    ON customer_import_staging (job_id, attempt_number, normalized_external_id, row_number)
    WHERE validation_passed;

CREATE INDEX customer_import_staging_canonical_idx
    ON customer_import_staging (job_id, attempt_number, row_number)
    WHERE duplicate_rank = 1;

-- Validation issues live only until the report has been streamed and published.  Keeping them
-- beside source rows allows a report to be emitted in physical-row order without keeping an open
-- JDBC connection or buffering all errors in heap.
CREATE UNLOGGED TABLE customer_import_staging_issue (
    job_id         UUID         NOT NULL,
    attempt_number INTEGER      NOT NULL,
    row_number     BIGINT       NOT NULL,
    issue_order    INTEGER      NOT NULL,
    -- This is the value written to the protected report, so it must also hold an invalid ID that
    -- exceeded the 64-character business limit.
    external_id    TEXT,
    error_code     VARCHAR(100) NOT NULL,
    field_name     VARCHAR(100) NOT NULL,
    error_message  VARCHAR(500) NOT NULL,

    CONSTRAINT customer_import_staging_issue_pk
        PRIMARY KEY (job_id, attempt_number, row_number, issue_order),
    CONSTRAINT customer_import_staging_issue_order_chk CHECK (issue_order >= 0)
);

CREATE INDEX customer_import_staging_issue_report_idx
    ON customer_import_staging_issue (job_id, attempt_number, row_number, issue_order);
