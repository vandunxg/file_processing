CREATE TABLE audit_logs (
    id                    UUID PRIMARY KEY,
    domain                VARCHAR(50)  NOT NULL,
    object_id             UUID,
    operation             VARCHAR(50)  NOT NULL,
    changed_by            UUID,
    changed_at            TIMESTAMPTZ  NOT NULL,
    data                  JSONB,
    ip_address            VARCHAR(64),
    browser               VARCHAR(64),
    user_agent            VARCHAR(200),
    deleted_at            TIMESTAMPTZ,
    created_by            VARCHAR(100),
    created_at            TIMESTAMPTZ  NOT NULL,
    last_modified_by      VARCHAR(100),
    last_modified_at      TIMESTAMPTZ  NOT NULL
);

CREATE INDEX audit_logs_domain_op_time_idx
    ON audit_logs (domain, operation, changed_at DESC);

CREATE INDEX audit_logs_actor_time_idx
    ON audit_logs (changed_by, changed_at DESC)
    WHERE changed_by IS NOT NULL;

CREATE INDEX audit_logs_object_time_idx
    ON audit_logs (object_id, changed_at DESC)
    WHERE object_id IS NOT NULL;
