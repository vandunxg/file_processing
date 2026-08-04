CREATE TABLE customers (
    id                  UUID PRIMARY KEY,
    external_id         VARCHAR(64)  NOT NULL UNIQUE,
    full_name           VARCHAR(150) NOT NULL,
    email               VARCHAR(254) NOT NULL,
    phone               VARCHAR(12)  NOT NULL,
    date_of_birth       DATE         NOT NULL,
    address             VARCHAR(500),
    last_import_file_id UUID         NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_modified_at    TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version             BIGINT       NOT NULL DEFAULT 0
);
