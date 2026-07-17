CREATE TABLE role (
    id                    UUID PRIMARY KEY,
    role_inherited_id     UUID,
    code                  VARCHAR(50)   NOT NULL,
    name                  VARCHAR(100)  NOT NULL,
    description           VARCHAR(1000),
    is_const              BOOLEAN       NOT NULL DEFAULT FALSE,
    status                VARCHAR(20)   NOT NULL DEFAULT 'ACTIVE',
    deleted_at            TIMESTAMPTZ,
    version               BIGINT        NOT NULL DEFAULT 0,
    created_by            VARCHAR(100),
    created_at            TIMESTAMPTZ   NOT NULL,
    last_modified_by      VARCHAR(100),
    last_modified_at      TIMESTAMPTZ   NOT NULL,

    CONSTRAINT role_status_chk
        CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT role_inherited_fk
        FOREIGN KEY (role_inherited_id) REFERENCES role (id) ON DELETE SET NULL
);

CREATE UNIQUE INDEX role_code_uk
    ON role (code)
    WHERE deleted_at IS NULL;

CREATE INDEX role_active_status_idx
    ON role (status)
    WHERE deleted_at IS NULL;

CREATE INDEX role_deleted_at_idx
    ON role (deleted_at)
    WHERE deleted_at IS NOT NULL;
