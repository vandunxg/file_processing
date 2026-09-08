CREATE TABLE processing_job (
    id               UUID PRIMARY KEY,
    import_file_id   UUID         NOT NULL REFERENCES file_import(id),
    owner_id         UUID         NOT NULL,
    status           VARCHAR(30)  NOT NULL,
    processed_rows   BIGINT       NOT NULL DEFAULT 0,
    valid_rows       BIGINT       NOT NULL DEFAULT 0,
    invalid_rows     BIGINT       NOT NULL DEFAULT 0,
    inserted_rows    BIGINT       NOT NULL DEFAULT 0,
    updated_rows     BIGINT       NOT NULL DEFAULT 0,
    total_rows       BIGINT,
    progress_percent INTEGER,
    current_attempt  INTEGER      NOT NULL DEFAULT 0,
    error_code       VARCHAR(100),
    error_summary    VARCHAR(500),
    error_report_key VARCHAR(512),
    started_at       TIMESTAMPTZ,
    finished_at      TIMESTAMPTZ,
    heartbeat_at     TIMESTAMPTZ,
    created_by       VARCHAR(100),
    created_at       TIMESTAMPTZ  NOT NULL,
    last_modified_by VARCHAR(100),
    last_modified_at TIMESTAMPTZ  NOT NULL,
    deleted_at       TIMESTAMPTZ,
    version          BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT processing_job_import_file_uk UNIQUE (import_file_id),
    CONSTRAINT processing_job_status_chk CHECK (
        status IN (
            'QUEUED',
            'PROCESSING',
            'CANCELLATION_REQUESTED',
            'COMPLETED',
            'COMPLETED_WITH_ERRORS',
            'FAILED',
            'CANCELLED'
        )
    ),
    CONSTRAINT processing_job_counters_chk CHECK (
        processed_rows >= 0
        AND valid_rows >= 0
        AND invalid_rows >= 0
        AND inserted_rows >= 0
        AND updated_rows >= 0
        AND processed_rows = valid_rows + invalid_rows
        AND inserted_rows + updated_rows <= valid_rows
    ),
    CONSTRAINT processing_job_total_rows_chk CHECK (total_rows IS NULL OR total_rows >= processed_rows),
    CONSTRAINT processing_job_progress_percent_chk CHECK (
        progress_percent IS NULL OR progress_percent BETWEEN 0 AND 100
    )
);

CREATE INDEX processing_job_status_created_at_idx
    ON processing_job (status, created_at ASC);
CREATE INDEX processing_job_owner_created_at_idx
    ON processing_job (owner_id, created_at DESC);
CREATE INDEX processing_job_status_heartbeat_at_idx
    ON processing_job (status, heartbeat_at);

CREATE TABLE processing_attempt (
    id               UUID PRIMARY KEY,
    job_id           UUID         NOT NULL REFERENCES processing_job(id),
    attempt_number   INTEGER      NOT NULL,
    trigger          VARCHAR(30)  NOT NULL,
    status           VARCHAR(30)  NOT NULL,
    started_at       TIMESTAMPTZ  NOT NULL,
    finished_at      TIMESTAMPTZ,
    processed_rows   BIGINT       NOT NULL DEFAULT 0,
    valid_rows       BIGINT       NOT NULL DEFAULT 0,
    invalid_rows     BIGINT       NOT NULL DEFAULT 0,
    inserted_rows    BIGINT       NOT NULL DEFAULT 0,
    updated_rows     BIGINT       NOT NULL DEFAULT 0,
    error_code       VARCHAR(100),
    error_summary    VARCHAR(500),
    created_by       VARCHAR(100),
    created_at       TIMESTAMPTZ  NOT NULL,
    last_modified_by VARCHAR(100),
    last_modified_at TIMESTAMPTZ  NOT NULL,
    deleted_at       TIMESTAMPTZ,

    CONSTRAINT processing_attempt_job_number_uk UNIQUE (job_id, attempt_number),
    CONSTRAINT processing_attempt_trigger_chk CHECK (
        trigger IN ('INITIAL', 'USER_RETRY', 'ADMIN_RETRY', 'RECOVERY')
    ),
    CONSTRAINT processing_attempt_status_chk CHECK (
        status IN ('RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')
    ),
    CONSTRAINT processing_attempt_counters_chk CHECK (
        processed_rows >= 0
        AND valid_rows >= 0
        AND invalid_rows >= 0
        AND inserted_rows >= 0
        AND updated_rows >= 0
        AND processed_rows = valid_rows + invalid_rows
        AND inserted_rows + updated_rows <= valid_rows
    )
);

CREATE INDEX processing_attempt_job_attempt_idx
    ON processing_attempt (job_id, attempt_number DESC);
CREATE INDEX processing_attempt_status_started_at_idx
    ON processing_attempt (status, started_at);
CREATE UNIQUE INDEX processing_attempt_running_job_uk
    ON processing_attempt (job_id)
    WHERE status = 'RUNNING';

ALTER TABLE customers ADD COLUMN last_import_job_id UUID;
ALTER TABLE customers
    ADD CONSTRAINT customers_last_import_job_fk
    FOREIGN KEY (last_import_job_id) REFERENCES processing_job(id);
