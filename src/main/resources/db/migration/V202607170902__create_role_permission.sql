CREATE TABLE role_permission (
    id                    UUID PRIMARY KEY,
    role_id               UUID         NOT NULL,
    resource_code         VARCHAR(50)  NOT NULL,
    action                VARCHAR(20)  NOT NULL,
    resource_group        VARCHAR(255),
    deleted_at            TIMESTAMPTZ,
    created_by            VARCHAR(100),
    created_at            TIMESTAMPTZ  NOT NULL,
    last_modified_by      VARCHAR(100),
    last_modified_at      TIMESTAMPTZ  NOT NULL,

    CONSTRAINT role_permission_role_fk
        FOREIGN KEY (role_id) REFERENCES role (id) ON DELETE CASCADE
);

CREATE INDEX role_permission_role_id_idx
    ON role_permission (role_id);

CREATE INDEX role_permission_active_idx
    ON role_permission (role_id, resource_code, action)
    WHERE deleted_at IS NULL;

CREATE INDEX role_permission_resource_active_idx
    ON role_permission (resource_code, action)
    WHERE deleted_at IS NULL;

CREATE INDEX role_permission_deleted_at_idx
    ON role_permission (deleted_at)
    WHERE deleted_at IS NOT NULL;
