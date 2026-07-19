ALTER TABLE auth_sessions
    ALTER COLUMN refresh_token_hash TYPE VARCHAR(64),
    ALTER COLUMN ip_address_hash TYPE VARCHAR(64);
