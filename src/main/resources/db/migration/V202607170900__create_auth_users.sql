CREATE TABLE auth_users (
    id                    UUID PRIMARY KEY,
    username              VARCHAR(64)  NOT NULL,
    normalized_username   VARCHAR(64)  NOT NULL,
    email                 VARCHAR(254) NOT NULL,
    normalized_email      VARCHAR(254) NOT NULL,
    display_name          VARCHAR(150) NOT NULL,
    password_hash         VARCHAR(255) NOT NULL,
    status                VARCHAR(20)  NOT NULL,
    must_change_password  BOOLEAN      NOT NULL DEFAULT FALSE,
    failed_login_count    INTEGER      NOT NULL DEFAULT 0,
    last_failed_login_at  TIMESTAMPTZ,
    locked_until          TIMESTAMPTZ,
    credential_version    INTEGER      NOT NULL DEFAULT 1,
    last_login_at         TIMESTAMPTZ,
    password_changed_at   TIMESTAMPTZ  NOT NULL,
    email_verified_at     TIMESTAMPTZ,
    deleted_at            TIMESTAMPTZ,
    created_by            VARCHAR(100),
    created_at            TIMESTAMPTZ  NOT NULL,
    last_modified_by      VARCHAR(100),
    last_modified_at      TIMESTAMPTZ  NOT NULL,
    version               BIGINT       NOT NULL DEFAULT 0,

    CONSTRAINT auth_users_status_chk
        CHECK (status IN ('PENDING_VERIFY', 'ACTIVE', 'DISABLED'))
);

CREATE UNIQUE INDEX auth_users_normalized_username_uk
    ON auth_users (normalized_username)
    WHERE deleted_at IS NULL;

CREATE UNIQUE INDEX auth_users_normalized_email_uk
    ON auth_users (normalized_email)
    WHERE deleted_at IS NULL;

CREATE INDEX auth_users_status_created_at_idx
    ON auth_users (status, created_at DESC)
    WHERE deleted_at IS NULL;

CREATE INDEX auth_users_locked_until_idx
    ON auth_users (locked_until)
    WHERE locked_until IS NOT NULL AND deleted_at IS NULL;

CREATE INDEX auth_users_deleted_at_idx
    ON auth_users (deleted_at)
    WHERE deleted_at IS NOT NULL;
