CREATE TABLE auth_sessions (
    id                    UUID        PRIMARY KEY,
    user_id               UUID        NOT NULL REFERENCES auth_users(id),
    credential_version    INTEGER     NOT NULL,
    refresh_token_hash    CHAR(64)    NOT NULL,
    user_agent            VARCHAR(255),
    ip_address_hash       CHAR(64),
    issued_at             TIMESTAMPTZ NOT NULL,
    last_used_at          TIMESTAMPTZ NOT NULL,
    expires_at            TIMESTAMPTZ NOT NULL,
    revoked_at            TIMESTAMPTZ,
    revoked_reason        VARCHAR(32),
    deleted_at            TIMESTAMPTZ,
    created_by            VARCHAR(64),
    last_modified_by      VARCHAR(64),
    last_modified_at      TIMESTAMPTZ,
    version               BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT auth_sessions_refresh_hash_ck CHECK (refresh_token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT auth_sessions_ip_hash_ck      CHECK (ip_address_hash IS NULL OR ip_address_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX auth_sessions_user_active_idx
    ON auth_sessions (user_id, last_used_at DESC)
    WHERE deleted_at IS NULL AND revoked_at IS NULL;

CREATE INDEX auth_sessions_refresh_hash_idx
    ON auth_sessions (refresh_token_hash)
    WHERE deleted_at IS NULL AND revoked_at IS NULL;

CREATE INDEX auth_sessions_expires_at_idx
    ON auth_sessions (expires_at)
    WHERE deleted_at IS NULL;
