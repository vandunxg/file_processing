CREATE INDEX auth_password_reset_tokens_expiry_cleanup_idx
    ON auth_password_reset_tokens (expires_at, id);

CREATE INDEX auth_refresh_sessions_revoked_cleanup_idx
    ON auth_refresh_sessions (revoked_at, id)
    WHERE deleted_at IS NULL AND revoked_at IS NOT NULL;
