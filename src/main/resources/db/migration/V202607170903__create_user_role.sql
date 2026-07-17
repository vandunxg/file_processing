CREATE TABLE user_role (
    id                    UUID PRIMARY KEY,
    user_id               UUID         NOT NULL,
    role_id               UUID         NOT NULL,
    deleted_at            TIMESTAMPTZ,
    created_by            VARCHAR(100),
    created_at            TIMESTAMPTZ  NOT NULL,
    last_modified_by      VARCHAR(100),
    last_modified_at      TIMESTAMPTZ  NOT NULL,

    CONSTRAINT user_role_user_fk
        FOREIGN KEY (user_id) REFERENCES auth_users (id) ON DELETE CASCADE,
    CONSTRAINT user_role_role_fk
        FOREIGN KEY (role_id) REFERENCES role (id) ON DELETE CASCADE
);

CREATE INDEX user_role_active_user_idx
    ON user_role (user_id)
    WHERE deleted_at IS NULL;

CREATE INDEX user_role_active_role_idx
    ON user_role (role_id)
    WHERE deleted_at IS NULL;

CREATE INDEX user_role_deleted_at_idx
    ON user_role (deleted_at)
    WHERE deleted_at IS NOT NULL;
