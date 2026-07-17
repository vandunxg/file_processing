CREATE TABLE auth_email_verification_tokens (
    id              UUID PRIMARY KEY,
    user_id         UUID         NOT NULL,
    token_hash      CHAR(64)     NOT NULL UNIQUE,
    issued_at       TIMESTAMPTZ  NOT NULL,
    expires_at      TIMESTAMPTZ  NOT NULL,
    used_at         TIMESTAMPTZ,
    ip_address_hash VARCHAR(64),

    CONSTRAINT auth_email_verification_tokens_user_fk
        FOREIGN KEY (user_id) REFERENCES auth_users (id) ON DELETE CASCADE
);

CREATE INDEX auth_email_verification_tokens_active_user_idx
    ON auth_email_verification_tokens (user_id, expires_at)
    WHERE used_at IS NULL;

CREATE INDEX auth_email_verification_tokens_expiry_cleanup_idx
    ON auth_email_verification_tokens (expires_at);
