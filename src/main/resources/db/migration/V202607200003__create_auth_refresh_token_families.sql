CREATE TABLE auth_refresh_sessions (
    id                    UUID        PRIMARY KEY,
    user_id               UUID        NOT NULL REFERENCES auth_users(id),
    credential_version    INTEGER     NOT NULL,
    user_agent            VARCHAR(255),
    ip_address_hash       VARCHAR(64),
    issued_at             TIMESTAMPTZ NOT NULL,
    last_used_at          TIMESTAMPTZ NOT NULL,
    expires_at            TIMESTAMPTZ NOT NULL,
    revoked_at            TIMESTAMPTZ,
    revoked_reason        VARCHAR(32),
    deleted_at            TIMESTAMPTZ,
    created_by            VARCHAR(64),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_modified_by      VARCHAR(64),
    last_modified_at      TIMESTAMPTZ,
    version               BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT auth_refresh_sessions_ip_hash_ck
        CHECK (ip_address_hash IS NULL OR ip_address_hash ~ '^[0-9a-f]{64}$')
);

CREATE TABLE auth_refresh_tokens (
    id                    UUID        PRIMARY KEY,
    session_id            UUID        NOT NULL REFERENCES auth_refresh_sessions(id),
    parent_token_id       UUID        REFERENCES auth_refresh_tokens(id),
    token_hash            VARCHAR(64) NOT NULL UNIQUE,
    issued_at             TIMESTAMPTZ NOT NULL,
    consumed_at           TIMESTAMPTZ,
    revoked_at            TIMESTAMPTZ,
    CONSTRAINT auth_refresh_tokens_hash_ck CHECK (token_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX auth_refresh_sessions_user_active_idx
    ON auth_refresh_sessions (user_id, last_used_at DESC)
    WHERE deleted_at IS NULL AND revoked_at IS NULL;

CREATE INDEX auth_refresh_sessions_expires_at_idx
    ON auth_refresh_sessions (expires_at)
    WHERE deleted_at IS NULL;

CREATE INDEX auth_refresh_tokens_session_idx ON auth_refresh_tokens (session_id);
