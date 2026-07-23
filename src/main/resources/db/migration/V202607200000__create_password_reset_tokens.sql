CREATE TABLE auth_password_reset_tokens (
    id              UUID PRIMARY KEY,
    user_id         UUID         NOT NULL REFERENCES auth_users (id) ON DELETE CASCADE,
    token_hash      VARCHAR(64)  NOT NULL UNIQUE,
    issued_at       TIMESTAMPTZ  NOT NULL,
    expires_at      TIMESTAMPTZ  NOT NULL,
    used_at         TIMESTAMPTZ,
    ip_address_hash VARCHAR(64),

    CONSTRAINT auth_password_reset_tokens_hash_chk
        CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT auth_password_reset_tokens_ip_hash_chk
        CHECK (ip_address_hash IS NULL OR ip_address_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX auth_password_reset_tokens_active_user_idx
    ON auth_password_reset_tokens (user_id, expires_at)
    WHERE used_at IS NULL;
